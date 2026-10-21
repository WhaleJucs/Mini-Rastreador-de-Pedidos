package com.foody.delivery.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class LocalEnvironmentConfigurationTest {
    @TempDir
    Path directory;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(EnvironmentValidator.class)
                .withPropertyValues("app.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Test
    void localDefaultsMatchDevelopmentProxyAndBindOnlyToLoopback() {
        runner().withPropertyValues("spring.profiles.active=local").run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AppProperties.class);
            assertThat(properties.db().path()).isEqualTo("./data/foody-local.db");
            assertThat(properties.jwt().issuer()).isEqualTo("foody-local");
            assertThat(properties.cors().allowedOrigins()).containsExactly("http://127.0.0.1:5174");
            assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8081");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"hml", "prod"})
    void remoteProfilesDoNotInheritLocalDefaults(String profile) {
        runner().withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Configuração de ambiente inválida: APP_DB_PATH é obrigatório.");
        });
    }

    @Test
    void processInputsOverrideLocalDefaults() {
        runner().withPropertyValues("spring.profiles.active=local")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("local-process-fixture", Map.of(
                                "APP_DB_PATH", "./fixture/override.db", "APP_JWT_ISSUER", "foody-local-fixture",
                                "APP_CORS_ALLOWED_ORIGINS", "http://localhost:5175",
                                "SERVER_ADDRESS", "localhost", "SERVER_PORT", "8082"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(AppProperties.class);
                    assertThat(properties.db().path()).isEqualTo("./fixture/override.db");
                    assertThat(properties.jwt().issuer()).isEqualTo("foody-local-fixture");
                    assertThat(properties.cors().allowedOrigins()).containsExactly("http://localhost:5175");
                    assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("localhost");
                    assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8082");
                });
    }

    @Test
    void localStartsWithoutSecretAndCreatesTheDatabaseDirectory() {
        var database = directory.resolve("nested").resolve("foody.db");
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(DatabaseConfig.class, JwtConfig.class)
                .withPropertyValues("spring.profiles.active=local", "app.db.path=" + database,
                        "app.jwt.secret-base64=", "app.jwt.issuer=foody-local",
                        "app.cors.allowed-origins=http://127.0.0.1:5174")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SecretKey.class).getEncoded()).hasSize(32);
                    assertThat(Files.isDirectory(database.getParent())).isTrue();
                    assertThat(Files.isRegularFile(database)).isTrue();
                });
    }
}
