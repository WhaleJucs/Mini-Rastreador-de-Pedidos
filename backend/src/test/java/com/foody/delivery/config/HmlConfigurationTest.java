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
class HmlConfigurationTest {
    @TempDir
    Path directory;

    private Map<String, Object> inputs(String profile) {
        return new HashMap<>(Map.of(
                "SPRING_PROFILES_ACTIVE", profile,
                "APP_DB_PATH", directory.resolve(profile + ".db").toString(),
                "APP_JWT_SECRET_BASE64", profile.equals("hml")
                        ? "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
                        : "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
                "APP_JWT_ISSUER", "foody-" + profile + "-fixture",
                "APP_CORS_ALLOWED_ORIGINS", "https://" + profile + ".example.invalid"));
    }

    private ApplicationContextRunner runner(Map<String, Object> inputs) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("hml-process-fixture", inputs)))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(DatabaseConfig.class);
    }

    @Test
    void loadsExplicitHmlInputsAndSafeLoggingAndErrorPolicy() {
        runner(inputs("hml")).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AppProperties.class);
            assertThat(properties.db().path()).isEqualTo(directory.resolve("hml.db").toString());
            assertThat(properties.jwt().issuer()).isEqualTo("foody-hml-fixture");
            assertThat(properties.cors().allowedOrigins()).containsExactly("https://hml.example.invalid");
            var environment = context.getEnvironment();
            assertThat(environment.getActiveProfiles()).containsExactly("hml");
            assertThat(environment.getProperty("logging.level.org.springframework.jdbc")).isEqualTo("OFF");
            assertThat(environment.getProperty("logging.level.org.springframework.web")).isEqualTo("WARN");
            assertThat(environment.getProperty("logging.level.org.springframework.security")).isEqualTo("WARN");
            for (String name : new String[] {"debug", "trace", "spring.mvc.log-request-details",
                    "server.tomcat.accesslog.enabled", "server.error.include-exception"}) {
                assertThat(environment.getProperty(name)).as(name).isEqualTo("false");
            }
            for (String name : new String[] {"message", "binding-errors", "stacktrace"}) {
                assertThat(environment.getProperty("server.error.include-" + name)).isEqualTo("never");
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP_DB_PATH", "APP_JWT_SECRET_BASE64", "APP_JWT_ISSUER", "APP_CORS_ALLOWED_ORIGINS"})
    void rejectsMissingInputBeforeCreatingDatabase(String missing, CapturedOutput output) {
        var inputs = inputs("hml");
        inputs.put(missing, "");
        runner(inputs).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(missing);
            assertThat(Files.exists(directory.resolve("hml.db"))).isFalse();
        });
        assertThat(output.getAll()).doesNotContain(inputs("hml").get("APP_JWT_SECRET_BASE64").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.invalid", "http://hml.example.invalid",
            "https://localhost:5174", "https://hml.example.invalid/path", ""})
    void rejectsUnsafeOriginsBeforeCreatingDatabase(String origin) {
        var inputs = inputs("hml");
        inputs.put("APP_CORS_ALLOWED_ORIGINS", origin);
        runner(inputs).run(context -> {
            assertThat(context).hasFailed();
            assertThat(Files.exists(directory.resolve("hml.db"))).isFalse();
        });
    }

    @Test
    void isolatesHmlFromLocalAndRetainsPerConnectionSqliteConfiguration() {
        runner(inputs("local")).run(local -> {
            assertThat(local).hasNotFailed();
            var localJdbc = new JdbcTemplate(local.getBean(DataSource.class));
            localJdbc.update("INSERT INTO users(name,email,password_hash) VALUES (?,?,?)",
                    "Fixture", "fixture@example.invalid", "synthetic-hash");
            runner(inputs("hml")).run(hml -> {
                assertThat(hml).hasNotFailed();
                var jdbc = new JdbcTemplate(hml.getBean(DataSource.class));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
                var hmlProperties = hml.getBean(AppProperties.class);
                var localProperties = local.getBean(AppProperties.class);
                assertThat(hmlProperties.jwt().secretBase64()).isNotEqualTo(localProperties.jwt().secretBase64());
                assertThat(hmlProperties.jwt().issuer()).isNotEqualTo(localProperties.jwt().issuer());
                for (int i = 0; i < 2; i++) {
                    assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
                    assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(5000);
                }
                jdbc.update("INSERT INTO users(name,email,password_hash) VALUES (?,?,?)",
                        "Hml fixture", "hml@example.invalid", "synthetic-hash");
                assertThat(localJdbc.queryForObject("SELECT email FROM users", String.class))
                        .isEqualTo("fixture@example.invalid");
            });
        });
    }
}
