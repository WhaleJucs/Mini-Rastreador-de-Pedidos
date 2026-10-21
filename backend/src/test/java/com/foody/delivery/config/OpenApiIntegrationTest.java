package com.foody.delivery.config;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiIntegrationTest {

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.db.path", () -> directory.resolve("openapi.db").toString());
        registry.add("app.jwt.secret-base64", () -> "");
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper mapper;

    @Test
    void exposesLocalOpenApiWithBearerSecurityAndExpectedVersionDescription() throws Exception {
        var response = http.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode document = mapper.readTree(response.getBody());
        assertThat(document.path("info").path("title").asText()).isEqualTo("Foody Delivery API");
        assertThat(document.path("components").path("securitySchemes").path("bearerAuth").path("scheme").asText())
                .isEqualTo("bearer");
        assertThat(document.path("components").path("schemas").path("StatusUpdateRequest")
                .path("properties").path("expectedVersion").path("description").asText())
                .contains("última leitura", "concorrentes");
    }
}
