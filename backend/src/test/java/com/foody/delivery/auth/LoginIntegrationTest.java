package com.foody.delivery.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;

import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(LoginIntegrationTest.ClockFixture.class)
@ExtendWith(OutputCaptureExtension.class)
class LoginIntegrationTest {

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REGISTER_PATH = "/api/auth/register";
    private static final String TEST_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final Instant INITIAL_TIME = Instant.parse("2026-10-20T12:00:00Z");

    @TempDir
    static Path databaseDirectory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("foody.test.database-path", () -> databaseDirectory.resolve("login.db").toString());
    }

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetFixture() {
        jdbcTemplate.update("DELETE FROM users");
        clock.setInstant(INITIAL_TIME);
    }

    @Test
    void logsInWithNormalizedEmailAndIssuesVerifiableHs256Token(CapturedOutput output) {
        var password = " SYNTHETIC-LOGIN-SECRET-DO-NOT-LOG ";
        var registered = register("Pessoa Login", "pessoa.login@example.com", password);

        var response = login(Map.of(
                "email", "  PESSOA.LOGIN@EXAMPLE.COM  ",
                "password", password));

        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn", "user");
        assertThat(response.getBody().path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(response.getBody().path("expiresIn").asLong()).isEqualTo(3600);
        assertThat(response.getBody().path("user").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "name", "email");
        assertThat(response.getBody().path("user").path("id").asLong())
                .isEqualTo(registered.getBody().path("id").asLong());
        assertThat(response.getBody().path("user").path("name").asText()).isEqualTo("Pessoa Login");
        assertThat(response.getBody().path("user").path("email").asText())
                .isEqualTo("pessoa.login@example.com");

        var tokenValue = response.getBody().path("accessToken").asText();
        assertThat(tokenValue).isNotBlank().doesNotContain(password);
        var jwt = jwtDecoder.decode(tokenValue);
        assertThat(jwt.getSubject()).isEqualTo(Long.toString(registered.getBody().path("id").asLong()));
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("foody-test");
        assertThat(jwt.getIssuedAt()).isEqualTo(INITIAL_TIME);
        assertThat(jwt.getExpiresAt()).isEqualTo(INITIAL_TIME.plusSeconds(3600));
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(output.getAll()).doesNotContain(password, tokenValue);
    }

    @Test
    void rejectsWrongPasswordAndUnknownEmailWithSameGenericProblem() {
        register("Pessoa Existente", "existente@example.com", "senha correta");

        var wrongPassword = login(Map.of(
                "email", "existente@example.com",
                "password", "senha errada"));
        var unknownEmail = login(Map.of(
                "email", "ausente@example.com",
                "password", "senha errada"));

        assertGenericInvalidCredentials(wrongPassword);
        assertGenericInvalidCredentials(unknownEmail);
        assertThat(wrongPassword.getBody()).isEqualTo(unknownEmail.getBody());
    }

    @Test
    void preservesPasswordCharactersInsteadOfTrimmingThem() {
        register("Pessoa Espaços", "espacos@example.com", " senha exata ");

        assertThat(login(Map.of("email", "espacos@example.com", "password", " senha exata "))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertGenericInvalidCredentials(login(Map.of(
                "email", "espacos@example.com",
                "password", "senha exata")));
    }

    @ParameterizedTest(name = "rejeita {0}")
    @MethodSource("invalidLogins")
    void rejectsInvalidLoginFields(String scenario, Map<String, Object> request, String expectedField) {
        var response = login(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().path("violations").findValuesAsText("field"))
                .contains(expectedField);
        assertThat(response.getBody().toString()).doesNotContain("rejectedValue", "accessToken", "password_hash");
    }

    static Stream<Arguments> invalidLogins() {
        return Stream.of(
                Arguments.of("e-mail ausente", Map.of("password", "senha"), "email"),
                Arguments.of("senha ausente", Map.of("email", "pessoa@example.com"), "password"),
                Arguments.of("e-mail em branco", Map.of("email", "   ", "password", "senha"), "email"),
                Arguments.of("e-mail inválido", Map.of("email", "invalido", "password", "senha"), "email"),
                Arguments.of("senha em branco", Map.of("email", "pessoa@example.com", "password", "   "), "password"),
                Arguments.of("senha acima de 72 bytes UTF-8", Map.of(
                        "email", "pessoa@example.com",
                        "password", "á".repeat(36) + "x"), "password"));
    }

    @Test
    void rejectsUnknownLoginFields() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = client.exchange(
                LOGIN_PATH,
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"email":"pessoa@example.com","password":"senha","role":"admin"}
                        """, headers),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void clockMakesExpirationDeterministicWithoutSleeping() {
        register("Pessoa Expiração", "expira@example.com", "senha");
        var response = login(Map.of("email", "expira@example.com", "password", "senha"));
        var tokenValue = response.getBody().path("accessToken").asText();

        assertThat(jwtDecoder.decode(tokenValue).getExpiresAt()).isEqualTo(INITIAL_TIME.plusSeconds(3600));
        clock.setInstant(INITIAL_TIME.plusSeconds(3661));
        assertThatThrownBy(() -> jwtDecoder.decode(tokenValue))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsTamperedTokenAndUnexpectedIssuer() {
        register("Pessoa Assinatura", "assinatura@example.com", "senha");
        var tokenValue = login(Map.of("email", "assinatura@example.com", "password", "senha"))
                .getBody().path("accessToken").asText();
        var segments = tokenValue.split("\\.");
        var firstPayloadCharacter = segments[1].charAt(0);
        segments[1] = (firstPayloadCharacter == 'a' ? 'b' : 'a') + segments[1].substring(1);
        var tampered = String.join(".", segments);

        assertThatThrownBy(() -> jwtDecoder.decode(tampered)).isInstanceOf(JwtException.class);

        var key = new SecretKeySpec(Base64.getDecoder().decode(TEST_SECRET), "HmacSHA256");
        var wrongIssuerDecoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        wrongIssuerDecoder.setJwtValidator(new JwtIssuerValidator("outro-emissor"));
        assertThatThrownBy(() -> wrongIssuerDecoder.decode(tokenValue))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void onlyPostLoginIsPublicAndOrderRoutesRemainDenied() {
        assertThat(client.getForEntity(LOGIN_PATH, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.getForEntity("/api/orders", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private org.springframework.http.ResponseEntity<JsonNode> register(
            String name,
            String email,
            String password) {
        return client.postForEntity(REGISTER_PATH, Map.of(
                "name", name,
                "email", email,
                "password", password), JsonNode.class);
    }

    private org.springframework.http.ResponseEntity<JsonNode> login(Map<String, Object> request) {
        return client.postForEntity(LOGIN_PATH, request, JsonNode.class);
    }

    private static void assertGenericInvalidCredentials(
            org.springframework.http.ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code");
        assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(response.getBody().path("detail").asText()).isEqualTo("E-mail ou senha inválidos.");
        assertThat(response.getBody().toString()).doesNotContain("accessToken", "password_hash");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockFixture {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(INITIAL_TIME, ZoneOffset.UTC);
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
