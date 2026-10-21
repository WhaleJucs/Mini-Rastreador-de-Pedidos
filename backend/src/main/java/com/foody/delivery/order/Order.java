package com.foody.delivery.order;

import java.time.Instant;
import java.util.List;

public record Order(
        long id,
        String customerName,
        DeliveryAddress deliveryAddress,
        List<OrderItem> items,
        OrderStatus status,
        Instant createdAt,
        long version) {

    public Order {
        items = List.copyOf(items);
    }
}
