package com.foody.delivery.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ProdConfigurationTest {
    @TempDir
    Path directory;

    private Map<String, Object> inputs(String profile) {
        return new HashMap<>(Map.of(
                "SPRING_PROFILES_ACTIVE", profile,
                "APP_DB_PATH", directory.resolve(profile + ".db").toString(),
                "APP_JWT_SECRET_BASE64", profile.equals("prod")
                        ? "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ="
                        : "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU=",
                "APP_JWT_ISSUER", "foody-" + profile + "-fixture",
                "APP_CORS_ALLOWED_ORIGINS", "https://" + profile + ".example.invalid"));
    }

    private ApplicationContextRunner runner(Map<String, Object> inputs) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("prod-process-fixture", inputs)))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(DatabaseConfig.class);
    }

    @Test
    void loadsOnlyExplicitProdInputsWithSafeErrorsLogsAndNoForwardedTrust() {
        runner(inputs("prod")).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AppProperties.class);
            assertThat(properties.db().path()).isEqualTo(directory.resolve("prod.db").toString());
            assertThat(properties.jwt().issuer()).isEqualTo("foody-prod-fixture");
            assertThat(properties.cors().allowedOrigins()).containsExactly("https://prod.example.invalid");
            assertThat(properties.toString()).doesNotContain(properties.jwt().secretBase64());
            var environment = context.getEnvironment();
            assertThat(environment.getActiveProfiles()).containsExactly("prod");
            assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("NONE");
            assertThat(environment.getProperty("logging.level.root")).isEqualTo("INFO");
            for (String logger : new String[] {"org.springframework.jdbc", "org.apache.catalina.core.ContainerBase"}) {
                assertThat(environment.getProperty("logging.level." + logger)).isEqualTo("OFF");
            }
            for (String logger : new String[] {"org.springframework.web", "org.springframework.security"}) {
                assertThat(environment.getProperty("logging.level." + logger)).isEqualTo("WARN");
            }
            for (String name : new String[] {"debug", "trace", "spring.mvc.log-request-details",
                    "server.tomcat.accesslog.enabled", "server.error.include-exception"}) {
                assertThat(environment.getProperty(name)).as(name).isEqualTo("false");
            }
            for (String name : new String[] {"message", "binding-errors", "stacktrace"}) {
                assertThat(environment.getProperty("server.error.include-" + name)).isEqualTo("never");
            }
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP_DB_PATH", "APP_JWT_SECRET_BASE64", "APP_JWT_ISSUER", "APP_CORS_ALLOWED_ORIGINS"})
    void rejectsMissingInputsBeforeCreatingDatabase(String missing, CapturedOutput output) {
        var inputs = inputs("prod");
        inputs.put(missing, "");
        runner(inputs).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(missing);
            assertThat(Files.exists(directory.resolve("prod.db"))).isFalse();
        });
        assertThat(output.getAll()).doesNotContain(inputs("prod").get("APP_JWT_SECRET_BASE64").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYNTHETIC-INVALID-SECRET-DO-NOT-LOG!", "YQ=="})
    void doesNotEchoInvalidSecretInDiagnostics(String secret, CapturedOutput output) {
        var inputs = inputs("prod");
        inputs.put("APP_JWT_SECRET_BASE64", secret);
        runner(inputs).run(context -> {
            assertThat(context).hasFailed();
            var trace = new java.io.StringWriter();
            context.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
            assertThat(trace.toString()).contains("APP_JWT_SECRET_BASE64").doesNotContain(secret);
            assertThat(Files.exists(directory.resolve("prod.db"))).isFalse();
        });
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative.db", ":memory:", "missing-parent", "directory"})
    void rejectsUnusableDatabasePathsWithSanitizedDiagnosis(String kind, CapturedOutput output) {
        var inputs = inputs("prod");
        String dbPath = switch (kind) {
            case "missing-parent" -> directory.resolve("SYNTHETIC-PRIVATE-PATH/missing.db").toString();
            case "directory" -> directory.toString();
            default -> kind;
        };
        inputs.put("APP_DB_PATH", dbPath);
        runner(inputs).run(context -> {
            assertThat(context).hasFailed();
            var trace = new java.io.StringWriter();
            context.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
            assertThat(trace.toString()).contains("APP_DB_PATH").doesNotContain(dbPath);
        });
        assertThat(output.getAll()).doesNotContain(dbPath);
        assertThat(Files.exists(directory.resolve("prod.db"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.invalid", "http://prod.example.invalid",
            "https://localhost:5174", "https://prod.example.invalid/path"})
    void rejectsUnsafeOriginsBeforeCreatingDatabase(String origin) {
        var inputs = inputs("prod");
        inputs.put("APP_CORS_ALLOWED_ORIGINS", origin);
        runner(inputs).run(context -> {
            assertThat(context).hasFailed();
            assertThat(Files.exists(directory.resolve("prod.db"))).isFalse();
        });
    }

    @Test
    void isolatesProdFromHmlAndPreservesDataAcrossContextRestart() {
        runner(inputs("hml")).run(hml -> {
            assertThat(hml).hasNotFailed();
            var hmlJdbc = new JdbcTemplate(hml.getBean(DataSource.class));
            hmlJdbc.update("INSERT INTO users(name,email,password_hash) VALUES (?,?,?)",
                    "Hml fixture", "hml@example.invalid", "synthetic-hash");
            runner(inputs("prod")).run(prod -> {
                assertThat(prod).hasNotFailed();
                var jdbc = new JdbcTemplate(prod.getBean(DataSource.class));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
                assertThat(prod.getBean(AppProperties.class).jwt().secretBase64())
                        .isNotEqualTo(hml.getBean(AppProperties.class).jwt().secretBase64());
                assertThat(prod.getBean(AppProperties.class).jwt().issuer())
                        .isNotEqualTo(hml.getBean(AppProperties.class).jwt().issuer());
                for (int i = 0; i < 2; i++) {
                    assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
                    assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(5000);
                }
                jdbc.update("INSERT INTO users(name,email,password_hash) VALUES (?,?,?)",
                        "Prod fixture", "prod@example.invalid", "synthetic-hash");
            });
            runner(inputs("prod")).run(restarted -> {
                assertThat(restarted).hasNotFailed();
                var jdbc = new JdbcTemplate(restarted.getBean(DataSource.class));
                assertThat(jdbc.queryForObject("SELECT email FROM users", String.class)).isEqualTo("prod@example.invalid");
            });
            assertThat(hmlJdbc.queryForObject("SELECT email FROM users", String.class)).isEqualTo("hml@example.invalid");
        });
    }
}
