package com.foody.delivery.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(CreateOrderIntegrationTest.ClockFixture.class)
class CreateOrderIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final String VALID_REQUEST = """
            {
              "customerName": "  Destinatário de teste  ",
              "deliveryAddress": {
                "street": "  Rua de Teste  ", "number": " S/N ",
                "city": " Cidade de Teste ", "state": " sp "
              },
              "items": [
                {"productName": " Produto A ", "quantity": 1},
                {"productName": "Produto B", "quantity": 2}
              ]
            }
            """;

    @TempDir
    static Path databaseDirectory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("foody.test.database-path", () -> databaseDirectory.resolve("create-orders.db").toString());
        registry.add("app.db.busy-timeout-ms", () -> 250);
    }

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JwtEncoder jwtEncoder;

    @BeforeEach
    void clearOrders() {
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("""
                INSERT OR IGNORE INTO users(id, name, email, password_hash)
                VALUES (1, 'Usuário de teste', 'owner@example.invalid', 'HASH_FICTICIO_NAO_UTILIZAVEL')
                """);
    }

    @Test
    void createsAndPersistsNormalizedOrderWithMultipleItemsAndServerMetadata() {
        var response = post(VALID_REQUEST, validToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "customerName", "deliveryAddress", "items", "status", "createdAt", "version");
        var orderId = body.path("id").asLong();
        assertThat(orderId).isPositive();
        assertThat(response.getHeaders().getLocation()).hasToString("/api/orders/" + orderId);
        assertThat(body.path("customerName").asText()).isEqualTo("Destinatário de teste");
        assertThat(body.path("status").asText()).isEqualTo("RECEBIDO");
        assertThat(body.path("version").asLong()).isZero();
        assertThat(body.path("createdAt").asText()).isEqualTo(NOW.toString());
        assertThat(body.path("deliveryAddress")).isEqualTo(mapper.valueToTree(new DeliveryAddress(
                "Rua de Teste", "S/N", "Cidade de Teste", "SP", null)));
        assertThat(body.path("items").size()).isEqualTo(2);
        assertThat(body.path("items").get(0).path("productName").asText()).isEqualTo("Produto A");
        assertThat(body.path("items").get(1).path("productName").asText()).isEqualTo("Produto B");

        // Read the committed data through a separate connection, not the HTTP transaction.
        var stored = jdbcTemplate.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
        assertThat(stored.get("customer_name")).isEqualTo("Destinatário de teste");
        assertThat(((Number) stored.get("user_id")).longValue()).isEqualTo(1L);
        assertThat(stored.get("street")).isEqualTo("Rua de Teste");
        assertThat(stored.get("number")).isEqualTo("S/N");
        assertThat(stored.get("city")).isEqualTo("Cidade de Teste");
        assertThat(stored.get("state")).isEqualTo("SP");
        assertThat(stored.get("zip_code")).isNull();
        assertThat(stored.get("status")).isEqualTo("RECEBIDO");
        assertThat(stored.get("created_at")).isEqualTo(NOW.toString());
        assertThat(((Number) stored.get("version")).longValue()).isZero();
        var storedItems = jdbcTemplate.queryForList(
                "SELECT id, order_id, product_name, quantity FROM order_items WHERE order_id = ? ORDER BY id", orderId);
        assertThat(storedItems).hasSize(2);
        for (var index = 0; index < storedItems.size(); index++) {
            var item = storedItems.get(index);
            var returned = body.path("items").get(index);
            assertThat(returned.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "productName", "quantity");
            assertThat(((Number) item.get("id")).longValue()).isEqualTo(returned.path("id").asLong()).isPositive();
            assertThat(((Number) item.get("order_id")).longValue()).isEqualTo(orderId);
            assertThat(item.get("product_name")).isEqualTo(returned.path("productName").asText());
            assertThat(((Number) item.get("quantity")).intValue()).isEqualTo(index + 1);
        }
        assertThat(body.path("items").get(0).path("id").asLong())
                .isLessThan(body.path("items").get(1).path("id").asLong());
    }

    @Test
    void acceptsOneItemAndKeepsDifferentOrdersIndependent() throws Exception {
        var first = post(VALID_REQUEST, validToken()).getBody();
        var request = (ObjectNode) mapper.readTree(VALID_REQUEST);
        request.put("customerName", "Outro destinatário");
        request.withArray("items").remove(1);
        var response = post(request.toString(), validToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var second = response.getBody();
        assertThat(second.path("id").asLong()).isNotEqualTo(first.path("id").asLong());
        assertThat(second.path("items").size()).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_items WHERE order_id = ?",
                Integer.class, first.path("id").asLong())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_items WHERE order_id = ?",
                Integer.class, second.path("id").asLong())).isOne();
    }

    @ParameterizedTest
    @MethodSource("validZipCodes")
    void acceptsOptionalOrFormattedZipCode(String zipCode, String expected) throws Exception {
        var request = (ObjectNode) mapper.readTree(VALID_REQUEST);
        var address = (ObjectNode) request.get("deliveryAddress");
        if (zipCode == null) {
            address.putNull("zipCode");
        } else {
            address.put("zipCode", zipCode);
        }
        var response = post(request.toString(), validToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var bodyZipCode = response.getBody().path("deliveryAddress").path("zipCode");
        if (expected == null) {
            assertThat(bodyZipCode.isNull()).isTrue();
        } else {
            assertThat(bodyZipCode.asText()).isEqualTo(expected);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT zip_code FROM orders", String.class)).isEqualTo(expected);
    }

    static Stream<Arguments> validZipCodes() {
        return Stream.of(Arguments.of(null, null), Arguments.of("00000-000", "00000000"),
                Arguments.of(" 00000000 ", "00000000"));
    }

    @Test
    void validatesTextLengthsAfterTrimmingAndAcceptsLargestInteger() throws Exception {
        var request = (ObjectNode) mapper.readTree(VALID_REQUEST);
        request.put("customerName", " " + "a".repeat(100) + " ");
        var address = (ObjectNode) request.get("deliveryAddress");
        address.put("street", " " + "a".repeat(150) + " ");
        address.put("city", " " + "b".repeat(150) + " ");
        address.put("number", " " + "n".repeat(30) + " ");
        var item = (ObjectNode) request.path("items").get(0);
        item.put("productName", " " + "p".repeat(100) + " ");
        item.put("quantity", Integer.MAX_VALUE);

        assertThat(post(request.toString(), validToken()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject("SELECT max(quantity) FROM order_items", Long.class))
                .isEqualTo((long) Integer.MAX_VALUE);
    }

    @ParameterizedTest(name = "recusa {0}")
    @MethodSource("invalidRequests")
    void rejectsInvalidPayloadWithoutPartialWrites(String scenario, String payload, String field) {
        var response = post(payload, validToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().path("violations").findValuesAsText("field")).contains(field);
        assertNoOrders();
    }

    static Stream<Arguments> invalidRequests() throws Exception {
        var cases = new java.util.ArrayList<Arguments>();
        var json = new ObjectMapper();
        for (var field : List.of("customerName", "deliveryAddress", "items")) {
            for (var nullValue : List.of(false, true)) {
                var request = (ObjectNode) json.readTree(VALID_REQUEST);
                if (nullValue) {
                    request.putNull(field);
                } else {
                    request.remove(field);
                }
                cases.add(Arguments.of(field + (nullValue ? " null" : " ausente"), request.toString(), field));
            }
        }
        cases.add(Arguments.of("lista vazia", VALID_REQUEST.replaceAll("(?s)\\[.*]", "[]"), "items"));
        cases.add(Arguments.of("item nulo", VALID_REQUEST.replaceAll("(?s)\\[.*]", "[null]"), "items[0]"));
        for (var value : List.of("null", "0", "-1", "1.5", "1.0", "\"2\"", "true", "2147483648")) {
            cases.add(Arguments.of("quantidade " + value,
                    VALID_REQUEST.replace("\"quantity\": 2", "\"quantity\": " + value), "items[1].quantity"));
        }
        var absentQuantity = (ObjectNode) json.readTree(VALID_REQUEST);
        ((ObjectNode) absentQuantity.path("items").get(1)).remove("quantity");
        cases.add(Arguments.of("quantidade ausente", absentQuantity.toString(), "items[1].quantity"));
        for (var field : List.of("street", "number", "city", "state")) {
            for (var value : new String[] {null, "   "}) {
                var request = (ObjectNode) json.readTree(VALID_REQUEST);
                var address = (ObjectNode) request.get("deliveryAddress");
                if (value == null) {
                    address.remove(field);
                } else {
                    address.put(field, value);
                }
                cases.add(Arguments.of("endereço " + field + " " + value, request.toString(), "deliveryAddress." + field));
            }
        }
        for (var field : List.of("customerName", "productName")) {
            for (var value : List.of("   ", "x".repeat(101))) {
                var request = (ObjectNode) json.readTree(VALID_REQUEST);
                var target = field.equals("customerName") ? request : (ObjectNode) request.path("items").get(1);
                target.put(field, value);
                cases.add(Arguments.of("texto " + field + " inválido", request.toString(),
                        field.equals("customerName") ? field : "items[1].productName"));
            }
        }
        for (var field : List.of("street", "city", "number", "state", "zipCode")) {
            var request = (ObjectNode) json.readTree(VALID_REQUEST);
            ((ObjectNode) request.get("deliveryAddress")).put(field, "x".repeat(switch (field) {
                case "street", "city" -> 151;
                case "number" -> 31;
                default -> 9;
            }));
            cases.add(Arguments.of("formato/limite " + field, request.toString(), "deliveryAddress." + field));
        }
        for (var zip : List.of("", "0000000", "000000000", "0000-0000", "abcdefgh")) {
            var request = (ObjectNode) json.readTree(VALID_REQUEST);
            ((ObjectNode) request.get("deliveryAddress")).put("zipCode", zip);
            cases.add(Arguments.of("CEP inválido", request.toString(), "deliveryAddress.zipCode"));
        }
        return cases.stream();
    }

    @Test
    void rejectsUnknownFieldsAtEachInputLevel() throws Exception {
        for (var field : List.of("id", "status", "version", "createdAt", "userId", "price", "phone")) {
            var request = (ObjectNode) mapper.readTree(VALID_REQUEST);
            request.put(field, "não permitido");
            assertValidationError(post(request.toString(), validToken()));
        }
        var address = (ObjectNode) mapper.readTree(VALID_REQUEST);
        ((ObjectNode) address.get("deliveryAddress")).put("bairro", "não permitido");
        assertValidationError(post(address.toString(), validToken()));
        var item = (ObjectNode) mapper.readTree(VALID_REQUEST);
        ((ObjectNode) item.path("items").get(0)).put("price", 10);
        assertValidationError(post(item.toString(), validToken()));
        assertValidationError(post("{not-json", validToken()));
        assertNoOrders();
    }

    @Test
    void authenticatesBeforeValidationAndRejectsInvalidTokensWithoutWriting() {
        var valid = validToken();
        var segments = valid.split("\\.");
        var changed = (segments[2].charAt(0) == 'a' ? "b" : "a") + segments[2].substring(1);
        var invalidSignature = segments[0] + "." + segments[1] + "." + changed;
        for (var token : new String[] {null, "malformed-token", invalidSignature,
                token("foody-test", NOW.minusSeconds(7200), NOW.minusSeconds(120)),
                token("another-issuer", NOW, NOW.plusSeconds(3600))}) {
            for (var payload : List.of(VALID_REQUEST, "{not-json")) {
                var response = post(payload, token);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(response.getBody().path("code").asText()).isEqualTo("UNAUTHORIZED");
            }
        }
        assertNoOrders();
    }

    @Test
    void rollsBackOrderAndFirstItemWhenSecondItemFailsAndRecovers() {
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_second_order_item BEFORE INSERT ON order_items
                WHEN NEW.product_name = 'Produto B'
                BEGIN SELECT RAISE(ABORT, 'controlled-order-item-failure'); END
                """);
        try {
            var response = post(VALID_REQUEST, validToken());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().path("code").asText()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.getBody().toString()).doesNotContain(
                    "controlled-order-item-failure", "INSERT", "SQLite", "stackTrace", "password");
            assertNoOrders();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER fail_second_order_item");
        }
        assertThat(post(VALID_REQUEST, validToken()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isEqualTo(2);
    }

    @Test
    @Timeout(10)
    void reportsFiniteDatabaseContentionAndRecoversWithoutPartialWrites() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                var response = post(VALID_REQUEST, validToken());
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(response.getBody().path("code").asText()).isEqualTo("DATABASE_BUSY");
            } finally {
                statement.execute("ROLLBACK");
            }
        }
        assertNoOrders();
        assertThat(post(VALID_REQUEST, validToken()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void assertValidationError(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    private void assertNoOrders() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
    }

    private ResponseEntity<JsonNode> post(String payload, String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return client.exchange("/api/orders", HttpMethod.POST, new HttpEntity<>(payload, headers), JsonNode.class);
    }

    private String validToken() {
        return token("foody-test", NOW, NOW.plusSeconds(3600));
    }

    private String token(String issuer, Instant issuedAt, Instant expiresAt) {
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("1")
                .issuedAt(issuedAt).expiresAt(expiresAt).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockFixture {
        @Bean
        @Primary
        Clock orderTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
