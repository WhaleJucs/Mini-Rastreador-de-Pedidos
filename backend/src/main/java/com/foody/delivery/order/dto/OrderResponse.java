package com.foody.delivery.order.dto;

import java.time.Instant;
import java.util.List;

import com.foody.delivery.order.DeliveryAddress;
import com.foody.delivery.order.Order;
import com.foody.delivery.order.OrderStatus;

public record OrderResponse(
        long id,
        String customerName,
        DeliveryAddress deliveryAddress,
        List<ItemResponse> items,
        OrderStatus status,
        Instant createdAt,
        long version) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.id(), order.customerName(), order.deliveryAddress(),
                order.items().stream()
                        .map(item -> new ItemResponse(item.id(), item.productName(), item.quantity()))
                        .toList(),
                order.status(), order.createdAt(), order.version());
    }

    public record ItemResponse(long id, String productName, int quantity) {
    }
}
