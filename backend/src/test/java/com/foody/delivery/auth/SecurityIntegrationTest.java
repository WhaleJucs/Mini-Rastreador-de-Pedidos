package com.foody.delivery.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(SecurityIntegrationTest.ClockFixture.class)
@ExtendWith(OutputCaptureExtension.class)
class SecurityIntegrationTest {

    private static final String REGISTER_PATH = "/api/auth/register";
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String ORDERS_PATH = "/api/orders";
    private static final String TEST_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final Instant INITIAL_TIME = Instant.parse("2026-10-20T12:00:00Z");
    private static final String ALLOWED_ORIGIN = "http://localhost:5174";

    @TempDir
    static Path databaseDirectory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("foody.test.database-path", () -> databaseDirectory.resolve("security.db").toString());
    }

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetFixture() {
        jdbcTemplate.update("DELETE FROM users");
        clock.setInstant(INITIAL_TIME);
    }

    @Test
    void protectsEveryPlannedOrderRouteBeforeBodyOrResourceLookup() {
        var requests = List.of(
                new ProtectedRequest(HttpMethod.POST, ORDERS_PATH, "{not-json"),
                new ProtectedRequest(HttpMethod.GET, ORDERS_PATH, null),
                new ProtectedRequest(HttpMethod.GET, ORDERS_PATH + "/1", null),
                new ProtectedRequest(HttpMethod.GET, ORDERS_PATH + "/999999", null),
                new ProtectedRequest(HttpMethod.PATCH, ORDERS_PATH + "/1/status", "{not-json"),
                new ProtectedRequest(HttpMethod.PATCH, ORDERS_PATH + "/999999/status", "{not-json"));

        requests.forEach(request -> assertUnauthorized(exchange(
                request.method(),
                request.path(),
                request.body(),
                new HttpHeaders()),
                request.path()));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void rejectsMalformedTamperedExpiredWrongIssuerAndWrongSignatureTokens(CapturedOutput output) {
        var validToken = registerAndLogin();
        var segments = validToken.split("\\.");
        var firstPayloadCharacter = segments[1].charAt(0);
        segments[1] = (firstPayloadCharacter == 'a' ? 'b' : 'a') + segments[1].substring(1);
        var tamperedToken = String.join(".", segments);

        var tokens = List.of(
                "malformed-token",
                tamperedToken,
                token(jwtEncoder, "foody-test", INITIAL_TIME.minusSeconds(7200), INITIAL_TIME.minusSeconds(120)),
                token(jwtEncoder, "foody-other-context", INITIAL_TIME, INITIAL_TIME.plusSeconds(3600)),
                token(wrongKeyEncoder(), "foody-test", INITIAL_TIME, INITIAL_TIME.plusSeconds(3600)));

        tokens.forEach(token -> {
            var headers = bearer(token);
            assertUnauthorized(exchange(HttpMethod.GET, ORDERS_PATH, null, headers), ORDERS_PATH);
        });

        assertThat(output.getAll()).doesNotContain(validToken, tamperedToken);
        tokens.forEach(token -> assertThat(output.getAll()).doesNotContain(token));
    }

    @Test
    void acceptsAValidIssuedTokenBeforeValidatingTheOrderPayload() {
        var token = registerAndLogin();

        var response = exchange(HttpMethod.POST, ORDERS_PATH, "{not-json", bearer(token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void allowsOnlyLegitimatePreflightFromConfiguredOrigin() {
        var headers = new HttpHeaders();
        headers.setOrigin(ALLOWED_ORIGIN);
        headers.setAccessControlRequestMethod(HttpMethod.GET);
        headers.setAccessControlRequestHeaders(List.of(HttpHeaders.AUTHORIZATION));

        var response = exchangeText(HttpMethod.OPTIONS, ORDERS_PATH, null, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
        assertThat(response.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.GET);
        assertThat(response.getHeaders().getAccessControlAllowHeaders())
                .anyMatch(header -> header.equalsIgnoreCase(HttpHeaders.AUTHORIZATION));
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isFalse();
    }

    @Test
    void deniesPreflightFromUnconfiguredOriginWithoutGrantingCorsAccess() {
        var headers = new HttpHeaders();
        headers.setOrigin("https://not-allowed.example");
        headers.setAccessControlRequestMethod(HttpMethod.GET);
        headers.setAccessControlRequestHeaders(List.of(HttpHeaders.AUTHORIZATION));

        var response = exchangeText(HttpMethod.OPTIONS, ORDERS_PATH, null, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void includesAllowedCorsPolicyOnUnauthorizedApiResponse() {
        var headers = new HttpHeaders();
        headers.setOrigin(ALLOWED_ORIGIN);

        var response = exchange(HttpMethod.GET, ORDERS_PATH, null, headers);

        assertUnauthorized(response, ORDERS_PATH);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED_ORIGIN);
    }

    @Test
    void keepsPublicRoutesExactAndDoesNotTreatOrdinaryOptionsAsPreflight() {
        var jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

        assertThat(exchange(HttpMethod.POST, REGISTER_PATH, "{}", jsonHeaders).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(HttpMethod.POST, LOGIN_PATH, "{}", jsonHeaders).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertUnauthorized(exchange(HttpMethod.GET, LOGIN_PATH, null, new HttpHeaders()), LOGIN_PATH);
        assertUnauthorized(exchange(HttpMethod.GET, REGISTER_PATH, null, new HttpHeaders()), REGISTER_PATH);
        assertUnauthorized(exchange(HttpMethod.OPTIONS, ORDERS_PATH, null, new HttpHeaders()), ORDERS_PATH);
        assertUnauthorized(exchange(HttpMethod.GET, "/api/auth/not-a-route", null, new HttpHeaders()),
                "/api/auth/not-a-route");
        assertUnauthorized(exchange(HttpMethod.GET, "/error", null, new HttpHeaders()), "/error");
    }

    private String registerAndLogin() {
        var registration = client.postForEntity(REGISTER_PATH, Map.of(
                "name", "Pessoa Segurança",
                "email", "seguranca@example.com",
                "password", "senha sintética"), JsonNode.class);
        assertThat(registration.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var login = client.postForEntity(LOGIN_PATH, Map.of(
                "email", "seguranca@example.com",
                "password", "senha sintética"), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return login.getBody().path("accessToken").asText();
    }

    private org.springframework.http.ResponseEntity<JsonNode> exchange(
            HttpMethod method,
            String path,
            String body,
            HttpHeaders headers) {
        return client.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private org.springframework.http.ResponseEntity<String> exchangeText(
            HttpMethod method,
            String path,
            String body,
            HttpHeaders headers) {
        return client.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders bearer(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static void assertUnauthorized(
            org.springframework.http.ResponseEntity<JsonNode> response,
            String expectedPath) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code");
        assertThat(response.getBody().path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(response.getBody().path("instance").asText()).isEqualTo(expectedPath);
        assertThat(response.getBody().toString())
                .doesNotContain("stackTrace", "exception", "accessToken", "password_hash");
    }

    private static String token(JwtEncoder encoder, String issuer, Instant issuedAt, Instant expiresAt) {
        var claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("1")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static JwtEncoder wrongKeyEncoder() {
        var key = new SecretKeySpec("B".repeat(32).getBytes(StandardCharsets.US_ASCII), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
    }

    private record ProtectedRequest(HttpMethod method, String path, String body) {
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
