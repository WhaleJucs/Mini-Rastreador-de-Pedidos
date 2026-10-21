package com.foody.delivery.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
@Import(GetOrderIntegrationTest.ClockFixture.class)
class GetOrderIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    @TempDir
    static Path databaseDirectory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("foody.test.database-path", () -> databaseDirectory.resolve("get-order.db").toString());
    }

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    @BeforeEach
    void clearFixture() {
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void returnsTheCompleteOrderOnlyToItsOwner() {
        var firstToken = registerAndLogin("get-first@example.invalid");
        var secondToken = registerAndLogin("get-second@example.invalid");
        var firstOrder = create(firstToken, "Destinatário A", List.of(
                Map.of("productName", "Produto A", "quantity", 1),
                Map.of("productName", "Produto B", "quantity", 2)), true);
        var secondOrder = create(secondToken, "Destinatário B", List.of(
                Map.of("productName", "Produto C", "quantity", 3)), false);
        var firstId = firstOrder.path("id").asLong();
        jdbcTemplate.update("UPDATE orders SET status = ?, version = ? WHERE id = ?", "EM_PREPARO", 3, firstId);
        ((ObjectNode) firstOrder).put("status", "EM_PREPARO").put("version", 3);

        for (var pair : List.of(Map.entry(firstToken, firstOrder), Map.entry(secondToken, secondOrder))) {
            var expected = pair.getValue();
            var firstResponse = get(expected.path("id").asText(), pair.getKey());

            assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(firstResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(firstResponse.getBody()).isEqualTo(expected);
            assertThat(firstResponse.getBody().fieldNames()).toIterable().containsExactlyInAnyOrder(
                    "id", "customerName", "deliveryAddress", "items", "status", "createdAt", "version");
        }
        assertThat(firstOrder.path("items").get(0).path("id").asLong())
                .isLessThan(firstOrder.path("items").get(1).path("id").asLong());
        for (var pair : List.of(Map.entry(firstToken, secondOrder), Map.entry(secondToken, firstOrder))) {
            var foreign = get(pair.getValue().path("id").asText(), pair.getKey());
            var missing = get(Long.toString(Long.MAX_VALUE), pair.getKey());
            assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(foreign.getBody().path("code").asText()).isEqualTo("ORDER_NOT_FOUND");
            assertThat(foreign.getBody().path("detail")).isEqualTo(missing.getBody().path("detail"));
            assertThat(foreign.getBody().toString()).doesNotContain("Destinatário", "Produto", "userId", "user_id");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, firstId)).isEqualTo(3L);
    }

    @Test
    void returnsNotFoundForAValidMissingIdWithoutCreatingData() {
        var response = get(Long.toString(Long.MAX_VALUE), registerAndLogin("get-missing@example.invalid"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().path("code").asText()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(response.getBody().path("status").asInt()).isEqualTo(404);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "-1", "-9223372036854775808", "abc", "1.5", "9223372036854775808", "999999999999999999999999999" })
    void rejectsInvalidIdsAsValidationErrorsWithoutExposingInternalDetails(String id) {
        var response = get(id, registerAndLogin("get-invalid@example.invalid"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().path("violations").findValuesAsText("field")).containsExactly("id");
        assertThat(response.getBody().toString()).doesNotContain("NumberFormatException", "java.lang", "SELECT", "stackTrace");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    void authenticatesBeforeDisclosingExistenceOrValidatingTheId() {
        var validToken = registerAndLogin("get-protected@example.invalid");
        var order = create(validToken, "Destinatário protegido", List.of(
                Map.of("productName", "Produto protegido", "quantity", 1)), false);
        var segments = validToken.split("\\.");
        var changedSignature = (segments[2].charAt(0) == 'a' ? "b" : "a") + segments[2].substring(1);
        var tampered = segments[0] + "." + segments[1] + "." + changedSignature;

        for (var token : new String[] { null, "malformed-token", tampered,
                token("foody-test", NOW.minusSeconds(7200), NOW.minusSeconds(120)),
                token("wrong-issuer", NOW, NOW.plusSeconds(3600)) }) {
            for (var id : List.of(order.path("id").asText(), Long.toString(Long.MAX_VALUE), "0", "abc", "9223372036854775808")) {
                var response = get(id, token);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(response.getBody().path("code").asText()).isEqualTo("UNAUTHORIZED");
                assertThat(response.getBody().toString()).doesNotContain("Destinatário protegido", "Produto protegido", "ORDER_NOT_FOUND");
            }
        }
        assertThat(get(order.path("id").asText(), validToken).getBody()).isEqualTo(order);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isOne();
    }

    private String registerAndLogin(String email) {
        var registration = client.postForEntity("/api/auth/register",
                Map.of("name", "Usuário de teste", "email", email, "password", "senha de teste"), JsonNode.class);
        assertThat(registration.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var login = client.postForEntity("/api/auth/login",
                Map.of("email", email, "password", "senha de teste"), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return login.getBody().path("accessToken").asText();
    }

    private JsonNode create(String token, String customerName, List<?> items, boolean includeZipCode) {
        var address = new java.util.HashMap<String, Object>();
        address.put("street", "Rua de Teste");
        address.put("number", "S/N");
        address.put("city", "Cidade de Teste");
        address.put("state", "SP");
        if (includeZipCode) address.put("zipCode", "00000-000");
        var response = client.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(Map.of("customerName", customerName, "deliveryAddress", address, "items", items),
                        headers(token)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> get(String id, String token) {
        return client.exchange("/api/orders/" + id, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    private HttpHeaders headers(String token) {
        var headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return headers;
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
        Clock getOrderTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
