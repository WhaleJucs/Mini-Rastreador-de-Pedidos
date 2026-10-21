package com.foody.delivery.order;

public class InvalidOrderIdException extends RuntimeException {

    public InvalidOrderIdException() {
        super("Informe um identificador inteiro positivo válido.");
    }
}
