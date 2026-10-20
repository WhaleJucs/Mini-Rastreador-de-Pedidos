package com.foody.delivery.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentIsolationTest {
    private static final List<String> PROFILES = List.of("local", "hml", "prod", "test");

    @TempDir
    Path directory;

    private Map<String, Object> inputs(String profile) {
        var bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) (PROFILES.indexOf(profile) + 1));
        return new HashMap<>(Map.of(
                "SPRING_PROFILES_ACTIVE", profile,
                "APP_DB_PATH", directory.resolve(profile + ".db").toString(),
                "APP_JWT_SECRET_BASE64", Base64.getEncoder().encodeToString(bytes),
                "APP_JWT_ISSUER", "foody-" + profile + "-isolation-fixture",
                "APP_CORS_ALLOWED_ORIGINS", "https://" + profile + ".example.invalid",
                "APP_DB_BUSY_TIMEOUT_MS", "1234",
                "APP_JWT_TTL_SECONDS", "3600",
                "foody.test.database-path", directory.resolve("test.db").toString()));
    }

    private AnnotationConfigApplicationContext open(String profile) {
        var context = new AnnotationConfigApplicationContext();
        try {
            context.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource("isolation-fixture", inputs(profile)));
            new ConfigDataApplicationContextInitializer().initialize(context);
            context.register(DatabaseConfig.class);
            context.refresh();
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    @Test
    void isolatesAllContextsAndRollsBackOnlyTheSelectedDatabase() throws Exception {
        try (var local = open("local"); var hml = open("hml");
                var prod = open("prod"); var harness = open("test")) {
            var contexts = List.of(local, hml, prod, harness);
            var properties = contexts.stream().map(c -> c.getBean(AppProperties.class)).toList();
            assertThat(properties.stream().map(p -> p.db().path()).toList()).doesNotHaveDuplicates();
            assertThat(properties.stream().map(p -> p.jwt().secretBase64()).toList()).doesNotHaveDuplicates();
            assertThat(properties.stream().map(p -> p.jwt().issuer()).toList()).doesNotHaveDuplicates();
            assertThat(properties.stream().map(p -> p.cors().allowedOrigins()).toList()).doesNotHaveDuplicates();

            for (int i = 0; i < contexts.size(); i++) {
                var context = contexts.get(i);
                var profile = PROFILES.get(i);
                assertThat(context.getEnvironment().getActiveProfiles()).containsExactly(profile);
                var source = context.getBean(DataSource.class);
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
                insert(jdbc, profile + "@example.invalid");
                var transaction = new TransactionTemplate(context.getBean(JdbcTransactionManager.class));
                transaction.executeWithoutResult(status -> {
                    insert(jdbc, "rollback@example.invalid");
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(2);
                    status.setRollbackOnly();
                });
                // Independent physical connections must see the same file and connection policy.
                for (int connection = 0; connection < 2; connection++) {
                    try (var opened = source.getConnection()) {
                        var independent = new JdbcTemplate(new SingleConnectionDataSource(opened, true));
                        assertThat(independent.queryForList("SELECT email FROM users", String.class))
                                .containsExactly(profile + "@example.invalid");
                        assertThat(independent.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
                        assertThat(independent.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(1234);
                        var file = Path.of(independent.queryForMap("PRAGMA database_list").get("file").toString());
                        assertThat(Files.isSameFile(file, directory.resolve(profile + ".db"))).isTrue();
                    }
                }
            }
            // Read again after every context has written, catching cross-context overwrites too.
            for (int i = 0; i < contexts.size(); i++) {
                var jdbc = new JdbcTemplate(contexts.get(i).getBean(DataSource.class));
                assertThat(jdbc.queryForList("SELECT email FROM users", String.class))
                        .containsExactly(PROFILES.get(i) + "@example.invalid");
            }
            var paths = properties.stream().map(p -> Path.of(p.db().path())).toList();
            for (int i = 0; i < paths.size(); i++) {
                for (int j = i + 1; j < paths.size(); j++) {
                    assertThat(Files.isSameFile(paths.get(i), paths.get(j))).isFalse();
                }
            }
        }
        for (String profile : PROFILES) {
            try (var restarted = open(profile)) {
                var jdbc = new JdbcTemplate(restarted.getBean(DataSource.class));
                assertThat(jdbc.queryForList("SELECT email FROM users", String.class))
                        .containsExactly(profile + "@example.invalid");
            }
        }
        // @TempDir removes only these fixtures after the contexts/connections have closed.
    }

    private static void insert(JdbcTemplate jdbc, String email) {
        jdbc.update("INSERT INTO users(name,email,password_hash) VALUES (?,?,?)",
                "Synthetic fixture", email, "synthetic-not-a-real-password-hash");
    }

    @ParameterizedTest
    @CsvSource({"local,APP_DB_PATH", "hml,APP_DB_PATH", "prod,APP_DB_PATH",
            "local,APP_CORS_ALLOWED_ORIGINS", "hml,APP_CORS_ALLOWED_ORIGINS", "prod,APP_CORS_ALLOWED_ORIGINS"})
    void rejectsMissingPathOrUnsafeCorsBeforeAnyFileIsCreated(String profile, String field) {
        var inputs = inputs(profile);
        inputs.put(field, field.equals("APP_DB_PATH") ? "" : "*");
        rejects(inputs, field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"local,hml", "hml,prod", "local,prod", "prod,test"})
    void rejectsAmbiguousSelectionBeforeAnyFileIsCreated(String profiles) {
        var inputs = inputs("local");
        inputs.put("SPRING_PROFILES_ACTIVE", profiles);
        rejects(inputs, "SPRING_PROFILES_ACTIVE");
    }

    private void rejects(Map<String, Object> inputs, String field) {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("invalid-isolation-fixture", inputs)))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(DatabaseConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(field);
                    for (String profile : PROFILES) {
                        assertThat(Files.exists(directory.resolve(profile + ".db"))).isFalse();
                    }
                });
    }
}
