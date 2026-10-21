package com.foody.delivery.auth;

import java.sql.Statement;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteException;

@Repository
public class AuthRepository {

    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_CONSTRAINT_UNIQUE = 2067;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;

    public AuthRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        transaction = new TransactionTemplate(transactionManager);
    }

    public User insert(String name, String email, String passwordHash) {
        try {
            return transaction.execute(status -> insertInCurrentTransaction(name, email, passwordHash));
        } catch (DataAccessException exception) {
            var errorCode = sqliteErrorCode(exception);
            if (errorCode == SQLITE_CONSTRAINT_UNIQUE) {
                throw new EmailAlreadyExistsException();
            }
            if (errorCode != null && (errorCode & 0xff) == SQLITE_BUSY) {
                throw new DatabaseBusyException(exception);
            }
            throw exception;
        }
    }

    public Optional<User> findByEmail(String email) {
        return jdbcTemplate.query("""
                        SELECT id, name, email, password_hash
                        FROM users
                        WHERE email = ?
                        """,
                (resultSet, rowNumber) -> new User(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("email"),
                        resultSet.getString("password_hash")),
                email).stream().findFirst();
    }

    private User insertInCurrentTransaction(String name, String email, String passwordHash) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO users (name, email, password_hash)
                    VALUES (?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, email);
            statement.setString(3, passwordHash);
            return statement;
        }, keyHolder);

        var generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new IllegalStateException("O SQLite não retornou o identificador do usuário criado.");
        }
        return new User(generatedId.longValue(), name, email, passwordHash);
    }

    private static Integer sqliteErrorCode(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLiteException sqliteException) {
                return sqliteException.getResultCode().code;
            }
        }
        return null;
    }
}
