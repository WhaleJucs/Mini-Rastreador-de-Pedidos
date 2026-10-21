package com.foody.delivery.order;

public class OrderVersionConflictException extends RuntimeException {
    public OrderVersionConflictException() {
        super("O pedido foi alterado. Recarregue os dados antes de confirmar uma nova alteração.");
    }
}
