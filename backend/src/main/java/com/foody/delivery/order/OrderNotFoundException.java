package com.foody.delivery.order;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException() {
        super("O pedido informado não foi encontrado.");
    }
}
