package com.foody.delivery.order;

import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import com.foody.delivery.order.dto.CreateOrderRequest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OrderRepository {

    private static final String ORDER_SELECT = """
            SELECT o.id, o.customer_name, o.street, o.number, o.city, o.state, o.zip_code,
                   o.status, o.created_at, o.version,
                   i.id AS item_id, i.product_name, i.quantity
            FROM orders o
            LEFT JOIN order_items i ON i.order_id = o.id
            """;

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Order> findAll() {
        // Instant.toString() tem frações de segundo de tamanho variável. Remover apenas o Z
        // mantém a ordenação cronológica inclusive entre um segundo inteiro e sua fração.
        return jdbcTemplate.query(ORDER_SELECT + "ORDER BY rtrim(o.created_at, 'Z') DESC, o.id DESC, i.id ASC",
                (ResultSetExtractor<List<Order>>) OrderRepository::extractOrders);
    }

    public Optional<Order> findById(long id) {
        var orders = jdbcTemplate.query(ORDER_SELECT + "WHERE o.id = ? ORDER BY i.id ASC",
                (ResultSetExtractor<List<Order>>) OrderRepository::extractOrders, id);
        return orders.stream().findFirst();
    }

    private static List<Order> extractOrders(ResultSet resultSet) throws SQLException {
        var rows = new LinkedHashMap<Long, OrderRows>();
        while (resultSet.next()) {
            var orderId = resultSet.getLong("id");
            var orderRows = rows.get(orderId);
            if (orderRows == null) {
                orderRows = new OrderRows(orderId, resultSet.getString("customer_name"),
                        new DeliveryAddress(resultSet.getString("street"), resultSet.getString("number"),
                                resultSet.getString("city"), resultSet.getString("state"), resultSet.getString("zip_code")),
                        OrderStatus.valueOf(resultSet.getString("status")),
                        Instant.parse(resultSet.getString("created_at")), resultSet.getLong("version"),
                        new ArrayList<>());
                rows.put(orderId, orderRows);
            }
            var itemId = resultSet.getLong("item_id");
            if (!resultSet.wasNull()) {
                orderRows.items().add(new OrderItem(itemId, resultSet.getString("product_name"),
                        resultSet.getInt("quantity")));
            }
        }
        return rows.values().stream()
                .map(row -> new Order(row.id(), row.customerName(), row.deliveryAddress(), row.items(),
                        row.status(), row.createdAt(), row.version()))
                .toList();
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

    private record OrderRows(
            long id,
            String customerName,
            DeliveryAddress deliveryAddress,
            OrderStatus status,
            Instant createdAt,
            long version,
            List<OrderItem> items) {
    }
}
