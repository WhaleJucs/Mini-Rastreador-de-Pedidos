package com.foody.delivery.order;

public class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException() {
        super("A mudança de status solicitada não é permitida.");
    }
}
