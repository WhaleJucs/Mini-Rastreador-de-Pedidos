package com.foody.delivery.auth;

public class DatabaseBusyException extends RuntimeException {

    public DatabaseBusyException(Throwable cause) {
        super("O banco de dados está temporariamente ocupado.", cause);
    }
}
