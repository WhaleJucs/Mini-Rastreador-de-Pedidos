package com.foody.delivery.auth;

public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException() {
        super("Já existe uma conta cadastrada com este e-mail.");
    }
}
