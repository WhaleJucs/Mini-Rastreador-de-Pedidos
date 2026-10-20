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
@ActiveProfiles("hml")
@ExtendWith(OutputCaptureExtension.class)
@Import(HmlHttpConfigurationTest.ErrorFixture.class)
class HmlHttpConfigurationTest {
    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.db.path", () -> directory.resolve("hml-http.db").toString());
        registry.add("app.jwt.secret-base64", () -> "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=");
        registry.add("app.jwt.issuer", () -> "hml-http-fixture");
        registry.add("app.cors.allowed-origins", () -> "https://hml.example.invalid");
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
    void omitsInternalErrorDetailsEvenWhenRequested() {
        var response = client.getForEntity("/__hml-fixture/failure?trace=true&message=true", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).doesNotContain("INTERNAL-FIXTURE-DETAIL", "IllegalStateException",
                "HmlHttpConfigurationTest", "\"trace\"", "\"exception\"", "\"message\"");
    }

    // Apenas no harness: permite provocar o error dispatch sem abrir a API real.
    @TestConfiguration(proxyBeanMethods = false)
    @Import(FailureController.class)
    static class ErrorFixture {
        @Bean
        @Order(0)
        SecurityFilterChain errorFixtureChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/__hml-fixture/**", "/error")
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll()).build();
        }
    }

    @RestController
    static class FailureController {
        @GetMapping("/__hml-fixture/failure")
        void failure() {
            throw new IllegalStateException("INTERNAL-FIXTURE-DETAIL");
        }
    }
}
