package com.foody.delivery.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(UpdateOrderStatusIntegrationTest.ClockFixture.class)
class UpdateOrderStatusIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    @TempDir
    static Path databaseDirectory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("foody.test.database-path", () -> databaseDirectory.resolve("update-status.db").toString());
        registry.add("app.db.busy-timeout-ms", () -> 250);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private OrderRepository repository;

    private JsonNode first;
    private JsonNode second;
    private String token;

    @BeforeEach
    void prepareFixture() throws Exception {
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM users");
        token = token("foody-test", NOW, NOW.plusSeconds(3600));
        first = create("Destinatário A", token);
        second = create("Destinatário B", token);
    }

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    @ParameterizedTest(name = "{0} -> {1}: HTTP {2}")
    @MethodSource("statusPairs")
    void persistsOnlyAllowedChangesAndPreservesAllOtherFields(String current, String requested, int expectedHttp)
            throws Exception {
        long id = first.path("id").asLong();
        jdbcTemplate.update("UPDATE orders SET status = ?, version = 7 WHERE id = ?", current, id);
        var before = read(id);
        var storedBefore = storedOrders();
        var itemsBefore = storedItems();

        var response = patch(Long.toString(id), payload(requested, 7), token);

        assertThat(response.status()).isEqualTo(expectedHttp);
        if (expectedHttp == 200) {
            var expected = before.deepCopy();
            ((ObjectNode) expected).put("status", requested).put("version", current.equals(requested) ? 7 : 8);
            assertThat(response.body()).isEqualTo(expected);
            assertThat(response.contentType()).isEqualTo("application/json");
            assertThat(read(id)).isEqualTo(expected);
            assertThat(jdbcTemplate.queryForMap("SELECT * FROM orders WHERE id = ?", id))
                    .containsAllEntriesOf(mapWithoutStatusAndVersion(storedBefore.get(0)));
        } else {
            assertError(response, 400, "INVALID_STATUS_TRANSITION");
            assertThat(storedOrders()).isEqualTo(storedBefore);
        }
        assertThat(read(second.path("id").asLong())).isEqualTo(second);
        assertThat(storedItems()).isEqualTo(itemsBefore);
        var listed = send("GET", "/api/orders", null, token);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body()).isEqualTo(mapper.valueToTree(List.of(second, read(id))));
    }

    private static Stream<Arguments> statusPairs() {
        var statuses = new String[] { "RECEBIDO", "EM_PREPARO", "SAIU_PARA_ENTREGA", "ENTREGUE", "CANCELADO" };
        var allowed = new int[][] {
                { 200, 200, 400, 400, 200 },
                { 400, 200, 200, 400, 200 },
                { 400, 400, 200, 200, 400 },
                { 400, 400, 400, 200, 400 },
                { 400, 400, 400, 400, 200 }
        };
        return IntStream.range(0, 5).boxed().flatMap(row -> IntStream.range(0, 5)
                .mapToObj(column -> Arguments.of(statuses[row], statuses[column], allowed[row][column])));
    }

    @Test
    void compatibleRepetitionDoesNotExecuteUpdateAndStaleRevisionWinsOverRepetitionOrInvalidTransition()
            throws Exception {
        var id = first.path("id").asText();
        var changed = patch(id, payload("EM_PREPARO", 0), token);
        assertThat(changed.status()).isEqualTo(200);
        var before = storedOrders();
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_status_dml BEFORE UPDATE ON orders
                BEGIN SELECT RAISE(ABORT, 'unexpected-no-op-write'); END
                """);
        try {
            var repeated = patch(id, payload("EM_PREPARO", 1), token);
            assertThat(repeated.status()).isEqualTo(200);
            assertThat(repeated.body()).isEqualTo(changed.body());
            for (var requested : List.of("EM_PREPARO", "RECEBIDO", "CANCELADO")) {
                assertError(patch(id, payload(requested, 0), token), 409, "ORDER_VERSION_CONFLICT");
            }
            assertThat(storedOrders()).isEqualTo(before);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_status_dml");
        }
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void rejectsInvalidPayloadBeforeLookingUpTheOrder(String payload, String field) throws Exception {
        var before = storedOrders();
        var response = patch(Long.toString(Long.MAX_VALUE), payload, token);
        assertError(response, 400, "VALIDATION_ERROR");
        if (field != null) {
            assertThat(response.body().path("violations").findValuesAsText("field")).contains(field);
        }
        assertThat(storedOrders()).isEqualTo(before);
    }

    private static Stream<Arguments> invalidPayloads() {
        var cases = new java.util.ArrayList<Arguments>();
        for (var value : List.of("null", "-1", "0.0", "1.5", "\"0\"", "true", "[]", "{}",
                "9223372036854775808")) {
            cases.add(Arguments.of("{\"status\":\"EM_PREPARO\",\"expectedVersion\":" + value + "}", "expectedVersion"));
        }
        cases.add(Arguments.of("{\"status\":\"EM_PREPARO\"}", "expectedVersion"));
        for (var value : List.of("null", "0", "1.0", "true", "[]", "{}", "\"DONE\"", "\"em_preparo\"",
                "\" EM_PREPARO \"", "\"\"")) {
            cases.add(Arguments.of("{\"status\":" + value + ",\"expectedVersion\":0}", "status"));
        }
        cases.add(Arguments.of("{\"expectedVersion\":0}", "status"));
        for (var field : List.of("version", "id", "userId", "createdAt", "items", "deliveryAddress")) {
            cases.add(Arguments.of("{\"status\":\"EM_PREPARO\",\"expectedVersion\":0,\"" + field + "\":0}", field));
        }
        cases.add(Arguments.of("{not-json", null));
        return cases.stream();
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "-1", "abc", "1.5", "9223372036854775808" })
    void rejectsInvalidIdsWithoutMutating(String id) throws Exception {
        var before = storedOrders();
        var response = patch(id, payload("EM_PREPARO", 0), token);
        assertError(response, 400, "VALIDATION_ERROR");
        assertThat(response.body().path("violations").findValuesAsText("field")).contains("id");
        assertThat(storedOrders()).isEqualTo(before);
    }

    @Test
    void returnsNotFoundAndAcceptsLargestNonNegativeRevisionAsAConflict() throws Exception {
        var before = storedOrders();
        assertError(patch(Long.toString(Long.MAX_VALUE), payload("EM_PREPARO", 0), token), 404, "ORDER_NOT_FOUND");
        assertError(patch(first.path("id").asText(), payload("EM_PREPARO", Long.MAX_VALUE), token),
                409, "ORDER_VERSION_CONFLICT");
        assertThat(storedOrders()).isEqualTo(before);
    }

    @Test
    void authenticatesBeforeValidationAndExistenceChecks() throws Exception {
        var before = storedOrders();
        var parts = token.split("\\.");
        var signature = (parts[2].charAt(0) == 'a' ? "b" : "a") + parts[2].substring(1);
        var tampered = parts[0] + "." + parts[1] + "." + signature;
        for (var invalid : new String[] { null, "malformed-token", tampered,
                token("foody-test", NOW.minusSeconds(7200), NOW.minusSeconds(120)),
                token("other-issuer", NOW, NOW.plusSeconds(3600)) }) {
            for (var id : List.of(first.path("id").asText(), Long.toString(Long.MAX_VALUE), "abc")) {
                for (var body : List.of(payload("EM_PREPARO", 0), "{not-json")) {
                    assertError(patch(id, body, invalid), 401, "UNAUTHORIZED");
                }
            }
        }
        assertThat(storedOrders()).isEqualTo(before);
    }

    @Test
    void eitherAuthenticatedUserCanUpdateAnOrderWithoutOwnershipRules() throws Exception {
        var firstToken = registerAndLogin("update-first@example.invalid");
        var secondToken = registerAndLogin("update-second@example.invalid");
        var order = create("Destinatário compartilhado", firstToken);
        assertThat(patch(order.path("id").asText(), payload("EM_PREPARO", 0), secondToken).status()).isEqualTo(200);
        assertThat(patch(order.path("id").asText(), payload("SAIU_PARA_ENTREGA", 1), firstToken).status()).isEqualTo(200);
        assertThat(read(order.path("id").asLong()).path("version").asLong()).isEqualTo(2);
    }

    @Test
    void repositoryCompareAndSetRequiresTheIdRevisionAndObservedStatus() throws Exception {
        var id = first.path("id").asLong();
        var itemsBefore = storedItems();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(repository.updateStatus(Long.MAX_VALUE, 0, OrderStatus.RECEBIDO, OrderStatus.EM_PREPARO)).isZero();
            assertThat(repository.updateStatus(id, 1, OrderStatus.RECEBIDO, OrderStatus.EM_PREPARO)).isZero();
            assertThat(repository.updateStatus(id, 0, OrderStatus.EM_PREPARO, OrderStatus.SAIU_PARA_ENTREGA)).isZero();
            assertThat(repository.updateStatus(id, 0, OrderStatus.RECEBIDO, OrderStatus.EM_PREPARO)).isOne();
            assertThat(repository.updateStatus(id, 0, OrderStatus.RECEBIDO, OrderStatus.CANCELADO)).isZero();
        });
        var expected = first.deepCopy();
        ((ObjectNode) expected).put("status", "EM_PREPARO").put("version", 1);
        assertThat(read(id)).isEqualTo(expected);
        assertThat(read(second.path("id").asLong())).isEqualTo(second);
        assertThat(storedItems()).isEqualTo(itemsBefore);
    }

    @Test
    void zeroAffectedRowsIsAConflictForAnExistingOrder() throws Exception {
        var before = storedOrders();
        doReturn(0).when(repository).updateStatus(first.path("id").asLong(), 0, OrderStatus.RECEBIDO, OrderStatus.EM_PREPARO);
        assertError(patch(first.path("id").asText(), payload("EM_PREPARO", 0), token), 409, "ORDER_VERSION_CONFLICT");
        assertThat(storedOrders()).isEqualTo(before);
    }

    @Test
    void zeroAffectedRowsWithAbsenceIsNotFoundAndRollsBackTheTransaction() throws Exception {
        var id = first.path("id").asLong();
        var before = storedOrders();
        var itemsBefore = storedItems();
        doAnswer(invocation -> {
            jdbcTemplate.update("DELETE FROM order_items WHERE order_id = ?", id);
            jdbcTemplate.update("DELETE FROM orders WHERE id = ?", id);
            return 0;
        }).when(repository).updateStatus(id, 0, OrderStatus.RECEBIDO, OrderStatus.EM_PREPARO);
        assertError(patch(Long.toString(id), payload("EM_PREPARO", 0), token), 404, "ORDER_NOT_FOUND");
        assertThat(storedOrders()).isEqualTo(before);
        assertThat(storedItems()).isEqualTo(itemsBefore);
    }

    @Test
    @Timeout(15)
    void concurrentChangesUseIndependentConnectionsAndOnlyOneRevisionWins() throws Exception {
        var id = first.path("id").asLong();
        var connections = synchronizeFirstTwoReads(id);
        var itemsBefore = storedItems();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var preparing = executor.submit(() -> patch(Long.toString(id), payload("EM_PREPARO", 0), token));
            var cancelled = executor.submit(() -> patch(Long.toString(id), payload("CANCELADO", 0), token));
            var responses = List.of(preparing.get(8, TimeUnit.SECONDS), cancelled.get(8, TimeUnit.SECONDS));
            assertThat(responses.stream().filter(response -> response.status() == 200).count()).isEqualTo(1);
            var loser = responses.stream().filter(response -> response.status() != 200).findFirst().orElseThrow();
            assertThat(loser.status()).isIn(409, 503);
            assertError(loser, loser.status(), loser.status() == 409 ? "ORDER_VERSION_CONFLICT" : "DATABASE_BUSY");
            var winner = responses.stream().filter(response -> response.status() == 200).findFirst().orElseThrow();
            assertThat(read(id)).isEqualTo(winner.body());
            assertThat(winner.body().path("version").asLong()).isEqualTo(1);
            assertError(patch(Long.toString(id), payload(winner.body().path("status").asText(), 0), token),
                    409, "ORDER_VERSION_CONFLICT");
        }
        assertThat(connections).hasSize(2);
        assertThat(connections.get(0)).isNotSameAs(connections.get(1));
        assertThat(storedItems()).isEqualTo(itemsBefore);
        assertThat(read(second.path("id").asLong())).isEqualTo(second);
    }

    @Test
    @Timeout(15)
    void concurrentRepetitionUsesAConsistentReadWithoutBlockingTheRealChange() throws Exception {
        var id = first.path("id").asLong();
        var connections = synchronizeFirstTwoReads(id);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var repeated = executor.submit(() -> patch(Long.toString(id), payload("RECEBIDO", 0), token));
            var changed = executor.submit(() -> patch(Long.toString(id), payload("EM_PREPARO", 0), token));
            var noOp = repeated.get(8, TimeUnit.SECONDS);
            var update = changed.get(8, TimeUnit.SECONDS);
            assertThat(noOp.status()).isEqualTo(200);
            assertThat(noOp.body()).isEqualTo(first);
            assertThat(update.status()).isEqualTo(200);
            assertThat(update.body().path("version").asLong()).isEqualTo(1);
            assertThat(read(id)).isEqualTo(update.body());
        }
        assertThat(connections).hasSize(2);
        assertThat(connections.get(0)).isNotSameAs(connections.get(1));
    }

    private List<Connection> synchronizeFirstTwoReads(long id) {
        var barrier = new CyclicBarrier(2);
        var reads = new AtomicInteger();
        var connections = new CopyOnWriteArrayList<Connection>();
        doAnswer(invocation -> {
            var observed = invocation.callRealMethod();
            if (reads.getAndIncrement() < 2) {
                var connection = DataSourceUtils.getConnection(dataSource);
                try {
                    connections.add(connection);
                    barrier.await(5, TimeUnit.SECONDS);
                } finally {
                    DataSourceUtils.releaseConnection(connection, dataSource);
                }
            }
            return observed;
        }).when(repository).findById(id);
        return connections;
    }

    @Test
    @Timeout(10)
    void writerLockReturnsBusyAndRecoversWithoutChangingData() throws Exception {
        var before = storedOrders();
        var itemsBefore = storedItems();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                assertError(patch(first.path("id").asText(), payload("EM_PREPARO", 0), token), 503, "DATABASE_BUSY");
            } finally {
                statement.execute("ROLLBACK");
            }
        }
        assertThat(storedOrders()).isEqualTo(before);
        assertThat(storedItems()).isEqualTo(itemsBefore);
        assertThat(patch(first.path("id").asText(), payload("EM_PREPARO", 0), token).status()).isEqualTo(200);
    }

    @Test
    @Timeout(10)
    void readerLockMakesCommitFailAndRollbackReleasesTheConnection() throws Exception {
        var before = storedOrders();
        var itemsBefore = storedItems();
        assertThat(jdbcTemplate.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("delete");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("BEGIN");
            try {
                try (var result = statement.executeQuery("SELECT status FROM orders")) {
                    assertThat(result.next()).isTrue();
                }
                assertError(patch(first.path("id").asText(), payload("EM_PREPARO", 0), token), 503, "DATABASE_BUSY");
            } finally {
                statement.execute("ROLLBACK");
            }
        }
        assertThat(storedOrders()).isEqualTo(before);
        assertThat(storedItems()).isEqualTo(itemsBefore);
        var recovered = patch(first.path("id").asText(), payload("EM_PREPARO", 0), token);
        assertThat(recovered.status()).isEqualTo(200);
        assertThat(recovered.body().path("version").asLong()).isEqualTo(1);
    }

    @Test
    void persistenceFailureRollsBackAndReturnsSanitizedError() throws Exception {
        var before = storedOrders();
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_status_update AFTER UPDATE ON orders
                BEGIN SELECT RAISE(ABORT, 'controlled-status-failure'); END
                """);
        try {
            assertError(patch(first.path("id").asText(), payload("EM_PREPARO", 0), token), 500, "INTERNAL_ERROR");
            assertThat(storedOrders()).isEqualTo(before);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER fail_status_update");
        }
        assertThat(patch(first.path("id").asText(), payload("EM_PREPARO", 0), token).status()).isEqualTo(200);
    }

    private Reply patch(String id, String payload, String bearer) throws Exception {
        return send("PATCH", "/api/orders/" + id + "/status", payload, bearer);
    }

    private Reply send(String method, String path, String payload, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(6)).header("Content-Type", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        var response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), mapper.readTree(response.body()),
                response.headers().firstValue("Content-Type").orElse(""));
    }

    private JsonNode create(String customerName, String bearer) throws Exception {
        var body = Map.of("customerName", customerName,
                "deliveryAddress", Map.of("street", "Rua de Teste", "number", "S/N", "city", "Cidade de Teste", "state", "SP"),
                "items", List.of(Map.of("productName", "Produto A", "quantity", 1), Map.of("productName", "Produto B", "quantity", 2)));
        var response = send("POST", "/api/orders", mapper.writeValueAsString(body), bearer);
        assertThat(response.status()).isEqualTo(201);
        return response.body();
    }

    private JsonNode read(long id) throws Exception {
        var response = send("GET", "/api/orders/" + id, null, token);
        assertThat(response.status()).isEqualTo(200);
        return response.body();
    }

    private String registerAndLogin(String email) throws Exception {
        assertThat(send("POST", "/api/auth/register", mapper.writeValueAsString(
                Map.of("name", "Usuário de teste", "email", email, "password", "senha de teste")), null).status()).isEqualTo(201);
        var response = send("POST", "/api/auth/login", mapper.writeValueAsString(
                Map.of("email", email, "password", "senha de teste")), null);
        assertThat(response.status()).isEqualTo(200);
        return response.body().path("accessToken").asText();
    }

    private String token(String issuer, Instant issuedAt, Instant expiresAt) {
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("1").issuedAt(issuedAt).expiresAt(expiresAt).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static String payload(String status, long version) {
        return "{\"status\":\"" + status + "\",\"expectedVersion\":" + version + "}";
    }

    private List<Map<String, Object>> storedOrders() {
        return jdbcTemplate.queryForList("SELECT * FROM orders ORDER BY id");
    }

    private List<Map<String, Object>> storedItems() {
        return jdbcTemplate.queryForList("SELECT * FROM order_items ORDER BY id");
    }

    private static Map<String, Object> mapWithoutStatusAndVersion(Map<String, Object> stored) {
        var unchanged = new java.util.HashMap<>(stored);
        unchanged.remove("status");
        unchanged.remove("version");
        return unchanged;
    }

    private static void assertError(Reply response, int status, String code) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.contentType()).isEqualTo("application/problem+json");
        assertThat(response.body().path("status").asInt()).isEqualTo(status);
        assertThat(response.body().path("code").asText()).isEqualTo(code);
        assertThat(response.body().toString()).doesNotContain(
                "SQLite", "SELECT", "UPDATE", "stackTrace", "java.lang", "controlled-status-failure", "unexpected-no-op-write");
    }

    private record Reply(int status, JsonNode body, String contentType) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockFixture {
        @Bean
        @Primary
        Clock updateStatusTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
