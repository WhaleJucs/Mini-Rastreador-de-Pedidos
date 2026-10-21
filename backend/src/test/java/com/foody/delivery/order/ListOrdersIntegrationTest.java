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
@Import(ListOrdersIntegrationTest.ClockFixture.class)
class ListOrdersIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    @TempDir
    static Path databaseDirectory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("foody.test.database-path", () -> databaseDirectory.resolve("list-orders.db").toString());
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
    void returnsAnEmptyJsonArrayForAnAuthenticatedUser() {
        var response = list(registerAndLogin("first@example.invalid"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isArray()).isTrue();
        assertThat(response.getBody().size()).isZero();
    }

    @Test
    void returnsTheSameGlobalCollectionToBothUsersWithCompleteIndependentItems() {
        var firstToken = registerAndLogin("first@example.invalid");
        var secondToken = registerAndLogin("second@example.invalid");
        var firstOrder = create(firstToken, "Destinatário A", List.of(
                Map.of("productName", "Produto A", "quantity", 1),
                Map.of("productName", "Produto B", "quantity", 3)), false);
        var secondOrder = create(secondToken, "Destinatário B", List.of(
                Map.of("productName", "Produto C", "quantity", 2)), true);
        var thirdOrder = create(firstToken, "Destinatário C", List.of(
                Map.of("productName", "Produto D", "quantity", 4),
                Map.of("productName", "Produto E", "quantity", 5)), false);

        var firstResponse = list(firstToken);
        var secondResponse = list(secondToken);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstResponse.getBody().isArray()).isTrue();
        assertThat(firstResponse.getBody().size()).isEqualTo(3);
        assertThat(firstResponse.getBody()).isEqualTo(secondResponse.getBody());
        assertThat(firstResponse.getBody().get(0)).isEqualTo(thirdOrder);
        assertThat(firstResponse.getBody().get(1)).isEqualTo(secondOrder);
        assertThat(firstResponse.getBody().get(2)).isEqualTo(firstOrder);
        for (var order : firstResponse.getBody()) {
            assertThat(order.fieldNames()).toIterable().containsExactlyInAnyOrder(
                    "id", "customerName", "deliveryAddress", "items", "status", "createdAt", "version");
            assertThat(order.path("deliveryAddress").fieldNames()).toIterable().containsExactlyInAnyOrder(
                    "street", "number", "city", "state", "zipCode");
            for (var item : order.path("items")) {
                assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "productName", "quantity");
            }
        }
    }

    @Test
    void sortsByCreationTimeIncludingSubmillisecondPrecisionThenIdDescending() {
        var token = registerAndLogin("first@example.invalid");
        var items = List.of(Map.of("productName", "Produto", "quantity", 1));
        var latest = create(token, "Mais recente, ID menor", items, false);
        var oldest = create(token, "Mais antigo, ID maior", items, false);
        var wholeSecond = create(token, "Segundo inteiro", items, false);
        var fractionalSecond = create(token, "Fração posterior", items, false);
        var tiedFraction = create(token, "Fração empatada, ID maior", items, false);
        setCreatedAt(latest, NOW.plusSeconds(1));
        setCreatedAt(oldest, NOW.minusSeconds(1));
        setCreatedAt(wholeSecond, NOW);
        setCreatedAt(fractionalSecond, NOW.plusNanos(1));
        setCreatedAt(tiedFraction, NOW.plusNanos(1));

        var expected = List.of(latest, tiedFraction, fractionalSecond, wholeSecond, oldest);
        var firstResponse = list(token);
        var secondResponse = list(token);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstResponse.getBody().size()).isEqualTo(expected.size());
        for (var index = 0; index < expected.size(); index++) {
            assertThat(firstResponse.getBody().get(index)).isEqualTo(expected.get(index));
        }
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondResponse.getBody()).isEqualTo(firstResponse.getBody());
    }

    @Test
    void returnsPersistedStatusAndVersionWithoutDerivingThemFromCreationDefaults() {
        var token = registerAndLogin("first@example.invalid");
        var items = List.of(Map.of("productName", "Produto", "quantity", 1));
        for (var status : OrderStatus.values()) {
            var order = create(token, "Destinatário " + status.name(), items, false);
            jdbcTemplate.update("UPDATE orders SET status = ?, version = ? WHERE id = ?",
                    status.name(), 7, order.path("id").asLong());
        }

        var response = list(token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().size()).isEqualTo(OrderStatus.values().length);
        assertThat(response.getBody().findValuesAsText("status")).containsExactly(
                "CANCELADO", "ENTREGUE", "SAIU_PARA_ENTREGA", "EM_PREPARO", "RECEBIDO");
        for (var order : response.getBody()) {
            assertThat(order.path("version").asLong()).isEqualTo(7);
            assertThat(order.path("createdAt").asText()).isEqualTo(NOW.toString());
        }
    }

    @Test
    void rejectsMissingMalformedTamperedExpiredAndWrongIssuerTokensWithoutExposingOrders() {
        var valid = registerAndLogin("first@example.invalid");
        var order = create(valid, "Destinatário privado", List.of(
                Map.of("productName", "Produto privado", "quantity", 2)), false);
        var segments = valid.split("\\.");
        var alteredSignature = (segments[2].charAt(0) == 'a' ? "b" : "a") + segments[2].substring(1);
        var tampered = segments[0] + "." + segments[1] + "." + alteredSignature;
        for (var token : new String[] {null, "malformed-token", tampered,
                token("foody-test", NOW.minusSeconds(7200), NOW.minusSeconds(120)),
                token("wrong-issuer", NOW, NOW.plusSeconds(3600))}) {
            var response = list(token);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().path("code").asText()).isEqualTo("UNAUTHORIZED");
            assertThat(response.getBody().toString()).doesNotContain("Destinatário privado", "Produto privado");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isOne();
        assertThat(list(valid).getBody().get(0)).isEqualTo(order);
    }

    private void setCreatedAt(JsonNode order, Instant instant) {
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?", instant.toString(), order.path("id").asLong());
        ((ObjectNode) order).put("createdAt", instant.toString());
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
        if (includeZipCode) {
            address.put("zipCode", "00000-000");
        }
        var response = client.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(Map.of("customerName", customerName, "deliveryAddress", address, "items", items),
                        headers(token)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> list(String token) {
        return client.exchange("/api/orders", HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    private HttpHeaders headers(String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
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
        Clock listOrderTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
