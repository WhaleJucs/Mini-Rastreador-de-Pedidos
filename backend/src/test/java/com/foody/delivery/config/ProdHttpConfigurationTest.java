package com.foody.delivery.config;

import java.nio.file.Path;

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
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@ExtendWith(OutputCaptureExtension.class)
@Import(ProdHttpConfigurationTest.ErrorFixture.class)
class ProdHttpConfigurationTest {
    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.db.path", () -> directory.resolve("prod-http.db").toString());
        registry.add("app.jwt.secret-base64", () -> "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=");
        registry.add("app.jwt.issuer", () -> "prod-http-fixture");
        registry.add("app.cors.allowed-origins", () -> "https://prod.example.invalid");
    }

    @Autowired
    TestRestTemplate client;

    @Test
    void deniesProtectedApiWithoutLoggingAuthorizationOrBody(CapturedOutput output) {
        var headers = new HttpHeaders();
        headers.setBearerAuth("SYNTHETIC-AUTH-DO-NOT-LOG");
        var response = client.postForEntity("/api/orders",
                new HttpEntity<>("SYNTHETIC-ADDRESS-DO-NOT-LOG", headers), String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(output.getAll()).doesNotContain("SYNTHETIC-AUTH-DO-NOT-LOG", "SYNTHETIC-ADDRESS-DO-NOT-LOG");
    }

    @Test
    void omitsInternalErrorDetailsEvenWhenRequested(CapturedOutput output) {
        var response = client.getForEntity("/__prod-fixture/failure?trace=true&message=true", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).doesNotContain("SQL-SYNTHETIC-SECRET-DO-NOT-LOG", "IllegalStateException",
                "ProdHttpConfigurationTest", "\"trace\"", "\"exception\"", "\"message\"");
        assertThat(output.getAll()).doesNotContain("SQL-SYNTHETIC-SECRET-DO-NOT-LOG");
    }

    @Test
    void ignoresUntrustedForwardedHeaders() {
        var headers = new HttpHeaders();
        headers.set("Forwarded", "proto=https;host=spoofed.example.invalid");
        headers.set("X-Forwarded-Proto", "https");
        headers.set("X-Forwarded-Host", "spoofed.example.invalid");
        var response = client.exchange("/__prod-fixture/origin", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).startsWith("http://").doesNotContain("spoofed.example.invalid");
    }

    // Apenas no harness: permite provocar o error dispatch sem abrir a API real.
    @TestConfiguration(proxyBeanMethods = false)
    @Import(FailureController.class)
    static class ErrorFixture {
        @Bean
        @Order(0)
        SecurityFilterChain errorFixtureChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/__prod-fixture/**", "/error")
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll()).build();
        }
    }

    @RestController
    static class FailureController {
        @GetMapping("/__prod-fixture/origin")
        String origin(jakarta.servlet.http.HttpServletRequest request) {
            return request.getScheme() + "://" + request.getServerName();
        }

        @GetMapping("/__prod-fixture/failure")
        void failure() {
            throw new IllegalStateException("SQL-SYNTHETIC-SECRET-DO-NOT-LOG");
        }
    }
}
