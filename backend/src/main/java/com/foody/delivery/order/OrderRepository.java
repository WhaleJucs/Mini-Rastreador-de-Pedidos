package com.foody.delivery.order;

import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;

import com.foody.delivery.order.dto.CreateOrderRequest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Order insert(CreateOrderRequest request, Instant createdAt) {
        var address = request.deliveryAddress();
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO orders (customer_name, street, number, city, state, zip_code,
                                        status, created_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, 'RECEBIDO', ?, 0)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, request.customerName());
            statement.setString(2, address.street());
            statement.setString(3, address.number());
            statement.setString(4, address.city());
            statement.setString(5, address.state());
            statement.setString(6, address.zipCode());
            statement.setString(7, createdAt.toString());
            return statement;
        }, keyHolder);
        var orderId = generatedId(keyHolder);
        var items = new ArrayList<OrderItem>();
        for (var item : request.items()) {
            var itemKey = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO order_items (order_id, product_name, quantity)
                        VALUES (?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, orderId);
                statement.setString(2, item.productName());
                statement.setInt(3, item.quantity());
                return statement;
            }, itemKey);
            items.add(new OrderItem(generatedId(itemKey), item.productName(), item.quantity()));
        }
        return new Order(orderId, request.customerName(),
                new DeliveryAddress(address.street(), address.number(), address.city(), address.state(), address.zipCode()),
                items, OrderStatus.RECEBIDO, createdAt, 0);
    }

    private static long generatedId(GeneratedKeyHolder keyHolder) {
        var key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("O SQLite não retornou o identificador gerado.");
        }
        return key.longValue();
    }
}
