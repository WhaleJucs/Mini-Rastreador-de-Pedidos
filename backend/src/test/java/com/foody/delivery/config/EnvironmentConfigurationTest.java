package com.foody.delivery.config;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class EnvironmentConfigurationTest {
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @TempDir
    Path directory;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(EnvironmentValidator.class)
                .withPropertyValues("app.db.path=" + directory.resolve("fixture.db"),
                        "app.jwt.secret-base64=" + KEY, "app.jwt.issuer=foody-fixture",
                        "app.cors.allowed-origins=https://frontend.example.invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "hml", "prod", "test"})
    void acceptsOneExplicitProfileAndCommonDefaults(String profile) {
        runner().withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AppProperties.class);
            assertThat(properties.jwt().ttlSeconds()).isEqualTo(3600);
            assertThat(properties.db().busyTimeoutMs()).isEqualTo(5000);
            assertThat(properties.toString()).doesNotContain(KEY);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "local,hml", "prod,test", "local,unknown"})
    void rejectsMissingUnknownOrMultipleProfiles(String profiles) {
        runner().withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Configuração de ambiente inválida: SPRING_PROFILES_ACTIVE deve selecionar somente local, hml ou prod; test é exclusivo do harness.");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.db.path=", "app.db.path=:memory:", "app.db.path=file:shared.db",
            "app.db.busy-timeout-ms=0", "app.db.busy-timeout-ms=-1", "app.db.busy-timeout-ms=2147483648",
            "app.jwt.ttl-seconds=0", "app.jwt.ttl-seconds=-1", "app.jwt.ttl-seconds=invalid",
            "app.jwt.secret-base64=", "app.jwt.secret-base64=short", "app.jwt.secret-base64=YQ==",
            "app.jwt.issuer=", "app.cors.allowed-origins=", "app.cors.allowed-origins=*",
            "app.cors.allowed-origins=https://example.invalid/path",
            "app.cors.allowed-origins=https://user:password@example.invalid",
            "app.cors.allowed-origins=https://example.invalid?query=yes"})
    void rejectsInvalidProperties(String property) {
        runner().withPropertyValues("spring.profiles.active=local", property).run(context ->
                assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"hml", "prod"})
    void refusesLocalFallbacksInRemoteProfiles(String profile) {
        for (String property : new String[] {"app.db.path=relative.db",
                "app.cors.allowed-origins=http://frontend.example.invalid",
                "app.cors.allowed-origins=https://localhost:5174",
                "app.cors.allowed-origins=https://127.0.0.1:5174",
                "app.cors.allowed-origins=https://[::1]:5174"}) {
            runner().withPropertyValues("spring.profiles.active=" + profile, property).run(context ->
                    assertThat(context).hasFailed());
        }
    }

    @Test
    void rejectsInvalidConfigurationBeforeCreatingDatabase() {
        runner().withUserConfiguration(DatabaseConfig.class)
                .withPropertyValues("spring.profiles.active=local", "app.jwt.secret-base64=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(Files.exists(directory.resolve("fixture.db"))).isFalse();
                });
    }

    @Test
    void mapsProcessVariablesAndLetsThemOverrideYamlDefaults() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("synthetic-process", Map.of(
                                "SPRING_PROFILES_ACTIVE", "local", "APP_DB_PATH", directory.resolve("process.db").toString(),
                                "APP_DB_BUSY_TIMEOUT_MS", "1234", "APP_JWT_SECRET_BASE64", KEY,
                                "APP_JWT_ISSUER", "process-fixture", "APP_JWT_TTL_SECONDS", "7200",
                                "APP_CORS_ALLOWED_ORIGINS", "http://localhost:5174,https://frontend.example.invalid"))))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(EnvironmentValidator.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(AppProperties.class);
                    assertThat(properties.db().path()).isEqualTo(directory.resolve("process.db").toString());
                    assertThat(properties.db().busyTimeoutMs()).isEqualTo(1234);
                    assertThat(properties.jwt().secretBase64()).isEqualTo(KEY);
                    assertThat(properties.jwt().issuer()).isEqualTo("process-fixture");
                    assertThat(properties.jwt().ttlSeconds()).isEqualTo(7200);
                    assertThat(properties.cors().allowedOrigins()).containsExactly(
                            "http://localhost:5174", "https://frontend.example.invalid");
                });
    }

    @Test
    void doesNotExposeMalformedSecretInFailureOrLogs(CapturedOutput output) {
        var secret = "SYNTHETIC-MALFORMED-SECRET-DO-NOT-LOG!";
        runner().withPropertyValues("spring.profiles.active=local", "app.jwt.secret-base64=" + secret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("APP_JWT_SECRET_BASE64")
                            .hasStackTraceContaining("32 bytes");
                    assertThat(context.getStartupFailure().toString()).doesNotContain(secret);
                    var trace = new java.io.StringWriter();
                    context.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
                    assertThat(trace.toString()).doesNotContain(secret);
                });
        assertThat(output.getAll()).doesNotContain(secret);
    }
}
