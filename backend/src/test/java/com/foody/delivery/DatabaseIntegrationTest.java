package com.foody.delivery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import com.foody.delivery.config.DatabaseConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.sqlite.SQLiteException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@Timeout(15)
class DatabaseIntegrationTest {

    @TempDir
    Path directory;

    private AnnotationConfigApplicationContext context;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void openDatabase() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("database-test",
                Map.of("spring.datasource.url", "jdbc:sqlite:" + directory.resolve("integration.db"))));
        context.register(DatabaseConfig.class);
        context.refresh();
        dataSource = context.getBean(DataSource.class);
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(context.getBean(JdbcTransactionManager.class));
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void usesRealFileAndEnforcesForeignKeysOnIndependentConnections() throws Exception {
        try (var first = dataSource.getConnection(); var second = dataSource.getConnection()) {
            assertThat(first.getMetaData().getDatabaseProductName()).isEqualTo("SQLite");
            assertThat(first).isNotSameAs(second);
            for (var connection : new Connection[] {first, second}) {
                var connectionJdbc = new JdbcTemplate(new SingleConnectionDataSource(
                        connection, true));
                assertThat(connectionJdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
                assertThat(connectionJdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(3000);
                assertThat(connectionJdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualTo("delete");
                try (var statement = connection.prepareStatement(
                        "INSERT INTO order_items(order_id, product_name, quantity) VALUES (?, ?, ?)")) {
                    statement.setLong(1, 999);
                    statement.setString(2, "Item fictício");
                    statement.setInt(3, 1);
                    assertSqliteFailure(catchThrowable(statement::executeUpdate), 787);
                }
            }
        }
        assertThat(Files.size(directory.resolve("integration.db"))).isGreaterThan(0);
        assertThat(count("order_items")).isZero();
    }

    @Test
    void enforcesUniqueNormalizedEmail() {
        insertUser("a@example.invalid");
        assertSqliteFailure(catchThrowable(() -> insertUser("a@example.invalid")), 2067);
        assertSqliteFailure(catchThrowable(() -> insertUser(" A@example.invalid ")), 275);
        assertThat(count("users")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "2147483648"})
    void rejectsNonPositiveFractionalOrOversizedQuantity(String quantity) {
        insertOrder(1);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO order_items(order_id, product_name, quantity) VALUES (?, ?, ?)",
                1, "Item fictício", quantity)).hasRootCauseInstanceOf(SQLiteException.class);
        assertThat(count("order_items")).isZero();
    }

    @Test
    void enforcesStatusVersionAndRequiredAddressWhileAllowingNoZipCode() {
        insertOrder(1);
        assertThat(jdbc.queryForMap("SELECT status, version, number, zip_code FROM orders WHERE id = ?", 1))
                .containsEntry("status", "RECEBIDO").containsEntry("version", 0)
                .containsEntry("number", "S/N").containsEntry("zip_code", null);
        assertSqliteFailure(catchThrowable(() -> jdbc.update("UPDATE orders SET status = ? WHERE id = ?", "INVALIDO", 1)), 275);
        assertSqliteFailure(catchThrowable(() -> jdbc.update("UPDATE orders SET version = ? WHERE id = ?", -1, 1)), 275);
        for (var column : new String[] {"street", "number", "city", "state"}) {
            // Column names come only from this fixed test list; values remain parameters.
            assertSqliteFailure(catchThrowable(() -> jdbc.update("UPDATE orders SET " + column + " = ? WHERE id = ?", null, 1)), 1299);
        }
    }

    @Test
    void commitsMultipleItemsAndPreservesDataWhenSchemaIsInitializedAgain() {
        transaction.executeWithoutResult(status -> {
            insertOrder(1);
            insertItem(1, "Primeiro", 1);
            insertItem(1, "Segundo", 2);
        });
        context.close();
        openDatabase();
        assertThat(count("orders")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT product_name FROM order_items WHERE order_id = ? ORDER BY id", String.class, 1))
                .containsExactly("Primeiro", "Segundo");
        assertThat(jdbc.queryForList("SELECT quantity FROM order_items WHERE order_id = ? ORDER BY id", Integer.class, 1))
                .containsExactly(1, 2);
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }

    @Test
    void rollsBackEntireOrderAndRecoversAfterAnItemFails() {
        assertSqliteFailure(catchThrowable(() -> transaction.executeWithoutResult(status -> {
            insertOrder(1);
            insertItem(1, "Válido", 1);
            insertItem(1, "Inválido", 0);
        })), 275);
        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
        transaction.executeWithoutResult(status -> {
            insertOrder(2);
            insertItem(2, "Recuperado", 1);
        });
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_items")).isEqualTo(1);
    }

    @Test
    void endsBlockedWriteAndAllowsANewTransaction() throws Exception {
        try (var writer = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            try (var statement = writer.prepareStatement("INSERT INTO users(name, email, password_hash) VALUES (?, ?, ?)")) {
                statement.setString(1, "Usuário fictício");
                statement.setString(2, "locked@example.invalid");
                statement.setString(3, "HASH_FICTICIO_NAO_UTILIZAVEL");
                statement.executeUpdate();
            }
            assertSqliteFailure(catchThrowable(() -> transaction.executeWithoutResult(status -> insertUser("blocked@example.invalid"))), 5);
            writer.rollback();
        }
        assertThat(count("users")).isZero();
        transaction.executeWithoutResult(status -> insertUser("recovered@example.invalid"));
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    void rollsBackWhenAReaderBlocksCommitAndReleasesTransactionResources() throws Exception {
        insertUser("reader@example.invalid");
        Connection[] transactionConnection = new Connection[1];
        boolean[] writesCompleted = {false};
        try (var reader = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            try (var statement = reader.createStatement(); var rows = statement.executeQuery("SELECT id FROM users")) {
                assertThat(rows.next()).isTrue();
                var failure = catchThrowable(() -> transaction.executeWithoutResult(status -> {
                    transactionConnection[0] = DataSourceUtils.getConnection(dataSource);
                    insertOrder(1);
                    insertItem(1, "Não deve permanecer", 1);
                    writesCompleted[0] = true;
                }));
                assertThat(writesCompleted[0]).isTrue();
                assertSqliteFailure(failure, 5);
                assertThat(transactionConnection[0].isClosed()).isTrue();
                assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
            } finally {
                reader.rollback();
            }
        }
        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
        transaction.executeWithoutResult(status -> insertOrder(2));
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void usesPrimaryUniqueAndOrderItemIndexesForLookup() {
        insertUser("index@example.invalid");
        insertOrder(1);
        insertItem(1, "Item", 1);
        for (var query : new String[] {
                "SELECT * FROM orders WHERE id = 1",
                "SELECT * FROM users WHERE email = 'index@example.invalid'",
                "SELECT * FROM order_items WHERE order_id = 1 ORDER BY id"}) {
            var plans = jdbc.queryForList("EXPLAIN QUERY PLAN " + query);
            assertThat(plans).anySatisfy(plan -> assertThat(plan.get("detail").toString()).contains("SEARCH"));
        }
        assertThat(jdbc.queryForList("PRAGMA index_list('orders')")).isEmpty();
        assertThat(jdbc.queryForList("PRAGMA index_list('order_items')"))
                .singleElement().satisfies(index -> assertThat(index.get("name")).isEqualTo("idx_order_items_order_id"));
    }

    private void insertUser(String email) {
        jdbc.update("INSERT INTO users(name, email, password_hash) VALUES (?, ?, ?)",
                "Usuário fictício", email, "HASH_FICTICIO_NAO_UTILIZAVEL");
    }

    private void insertOrder(long id) {
        jdbc.update("""
                INSERT INTO orders(id, customer_name, street, number, city, state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, "Destinatário fictício", "Rua de Teste", "S/N", "Cidade de Teste", "SP", "2026-10-19T12:00:00Z");
    }

    private void insertItem(long orderId, String productName, int quantity) {
        jdbc.update("INSERT INTO order_items(order_id, product_name, quantity) VALUES (?, ?, ?)", orderId, productName, quantity);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void assertSqliteFailure(Throwable failure, int expectedCode) {
        assertThat(failure).isNotNull();
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        assertThat(failure).isInstanceOf(SQLiteException.class);
        assertThat(((SQLException) failure).getErrorCode()).isEqualTo(expectedCode & 0xff);
        assertThat(((SQLiteException) failure).getResultCode().code).isEqualTo(expectedCode);
    }
}
