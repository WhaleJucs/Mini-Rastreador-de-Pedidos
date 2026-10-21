package com.foody.delivery.order;

import java.util.Objects;

public final class OrderStatusTransitions {

    private OrderStatusTransitions() {
    }

    public enum Result {
        CHANGE,
        NO_OP,
        FORBIDDEN
    }

    public static Result classify(OrderStatus current, OrderStatus requested) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(requested, "requested");

        if (current == requested) {
            return Result.NO_OP;
        }

        boolean allowed = switch (current) {
            case RECEBIDO -> requested == OrderStatus.EM_PREPARO || requested == OrderStatus.CANCELADO;
            case EM_PREPARO -> requested == OrderStatus.SAIU_PARA_ENTREGA || requested == OrderStatus.CANCELADO;
            case SAIU_PARA_ENTREGA -> requested == OrderStatus.ENTREGUE;
            case ENTREGUE, CANCELADO -> false;
        };
        return allowed ? Result.CHANGE : Result.FORBIDDEN;
    }
}
