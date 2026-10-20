package com.foody.delivery.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RegistrationIntegrationTest {

    private static final String REGISTER_PATH = "/api/auth/register";

    @TempDir
    static Path databaseDirectory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("foody.test.database-path", () -> databaseDirectory.resolve("registration.db").toString());
        registry.add("app.db.busy-timeout-ms", () -> 250);
    }

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clearUsers() {
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void registersNormalizedUserWithoutAuthenticatingOrExposingCredentials() {
        var rawPassword = " senha válida com espaços ";
        var response = post(Map.of(
                "name", "  Maria da Silva  ",
                "email", "  MARIA.SILVA@EXAMPLE.COM  ",
                "password", rawPassword));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "name", "email");
        assertThat(response.getBody().path("id").asLong()).isPositive();
        assertThat(response.getBody().path("name").asText()).isEqualTo("Maria da Silva");
        assertThat(response.getBody().path("email").asText()).isEqualTo("maria.silva@example.com");

        var stored = jdbcTemplate.queryForMap("""
                SELECT name, email, password_hash
                FROM users
                WHERE id = ?
                """, response.getBody().path("id").asLong());
        assertThat(stored.get("name")).isEqualTo("Maria da Silva");
        assertThat(stored.get("email")).isEqualTo("maria.silva@example.com");
        assertThat(stored.get("password_hash")).isInstanceOf(String.class);
        assertThat((String) stored.get("password_hash")).startsWith("$2");
        assertThat(passwordEncoder.matches(rawPassword, (String) stored.get("password_hash"))).isTrue();
        assertThat(passwordEncoder.matches(rawPassword.trim(), (String) stored.get("password_hash"))).isFalse();
    }

    @Test
    void acceptsPasswordAtExactlySeventyTwoUtf8Bytes() {
        var password = "á".repeat(36);

        var response = post(Map.of(
                "name", "Limite BCrypt",
                "email", "limite@example.com",
                "password", password));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var storedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?",
                String.class,
                "limite@example.com");
        assertThat(passwordEncoder.matches(password, storedHash)).isTrue();
    }

    @ParameterizedTest(name = "rejeita {0}")
    @MethodSource("invalidRegistrations")
    void rejectsInvalidFields(String scenario, Map<String, Object> request, String expectedField) {
        var response = post(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().path("violations").findValuesAsText("field"))
                .contains(expectedField);
        assertThat(response.getBody().toString()).doesNotContain("rejectedValue", "password_hash");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    static Stream<Arguments> invalidRegistrations() {
        return Stream.of(
                Arguments.of("nome ausente", Map.of(
                        "email", "pessoa@example.com",
                        "password", "senha"), "name"),
                Arguments.of("e-mail ausente", Map.of(
                        "name", "Pessoa",
                        "password", "senha"), "email"),
                Arguments.of("senha ausente", Map.of(
                        "name", "Pessoa",
                        "email", "pessoa@example.com"), "password"),
                Arguments.of("nome em branco", Map.of(
                        "name", "   ",
                        "email", "pessoa@example.com",
                        "password", "senha"), "name"),
                Arguments.of("nome longo após normalização", Map.of(
                        "name", "a".repeat(101),
                        "email", "pessoa@example.com",
                        "password", "senha"), "name"),
                Arguments.of("e-mail inválido", Map.of(
                        "name", "Pessoa",
                        "email", "email-inválido",
                        "password", "senha"), "email"),
                Arguments.of("e-mail longo após normalização", Map.of(
                        "name", "Pessoa",
                        "email", "a".repeat(140) + "@example.com",
                        "password", "senha"), "email"),
                Arguments.of("senha composta só por espaços", Map.of(
                        "name", "Pessoa",
                        "email", "pessoa@example.com",
                        "password", "   "), "password"),
                Arguments.of("senha acima de 72 bytes UTF-8", Map.of(
                        "name", "Pessoa",
                        "email", "pessoa@example.com",
                        "password", "á".repeat(36) + "x"), "password"));
    }

    @Test
    void rejectsUnknownRegistrationFields() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = """
                {"name":"Pessoa","email":"pessoa@example.com","password":"senha","isAdmin":true}
                """;

        var response = client.exchange(
                REGISTER_PATH,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void rejectsDuplicateEmailAfterNormalization() {
        var first = post(Map.of(
                "name", "Primeira Pessoa",
                "email", "pessoa@example.com",
                "password", "senha inicial"));
        var duplicate = post(Map.of(
                "name", "Segunda Pessoa",
                "email", "  PESSOA@EXAMPLE.COM  ",
                "password", "outra senha"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(duplicate.getBody()).isNotNull();
        assertThat(duplicate.getBody().path("code").asText()).isEqualTo("EMAIL_ALREADY_EXISTS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?",
                Integer.class,
                "pessoa@example.com")).isOne();
    }

    @Test
    @Timeout(30)
    void serializesConcurrentRegistrationsOfTheSameNormalizedEmail() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> postAfterSignal(ready, start, Map.of(
                    "name", "Concorrente A",
                    "email", "concorrente@example.com",
                    "password", "senha A")));
            var second = executor.submit(() -> postAfterSignal(ready, start, Map.of(
                    "name", "Concorrente B",
                    "email", "  CONCORRENTE@EXAMPLE.COM  ",
                    "password", "senha B")));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var responses = Set.of(first.get(), second.get());
            assertThat(responses).extracting(response -> response.getStatusCode())
                    .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
            assertThat(responses)
                    .filteredOn(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                    .singleElement()
                    .satisfies(response -> assertThat(response.getBody().path("code").asText())
                            .isEqualTo("EMAIL_ALREADY_EXISTS"));
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?",
                Integer.class,
                "concorrente@example.com")).isOne();
    }

    @Test
    void reportsTemporaryDatabaseContentionSeparatelyFromDuplicateEmail() throws Exception {
        try (Connection lockConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(false);
            try (var statement = lockConnection.prepareStatement("""
                    INSERT INTO users (name, email, password_hash)
                    VALUES (?, ?, ?)
                    """)) {
                statement.setString(1, "Titular do lock");
                statement.setString(2, "lock@example.com");
                statement.setString(3, "$2a$10$fixture.nao.utilizada.em.autenticacao");
                statement.executeUpdate();
            }

            var response = post(Map.of(
                    "name", "Pessoa bloqueada",
                    "email", "bloqueada@example.com",
                    "password", "senha"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().path("code").asText()).isEqualTo("DATABASE_BUSY");
            lockConnection.rollback();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void keepsOtherApiRoutesProtected() {
        var response = client.getForEntity("/api/orders", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
    }

    private org.springframework.http.ResponseEntity<JsonNode> post(Map<String, Object> request) {
        return client.postForEntity(REGISTER_PATH, request, JsonNode.class);
    }

    private org.springframework.http.ResponseEntity<JsonNode> postAfterSignal(
            CountDownLatch ready,
            CountDownLatch start,
            Map<String, Object> request) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return post(request);
    }
}
