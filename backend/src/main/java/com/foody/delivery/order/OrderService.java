package com.foody.delivery.order;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import com.foody.delivery.auth.DatabaseBusyException;
import com.foody.delivery.order.dto.CreateOrderRequest;
import com.foody.delivery.order.dto.OrderResponse;
import com.foody.delivery.order.dto.StatusUpdateRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteException;

@Service
public class OrderService {

    private final OrderRepository repository;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public OrderService(OrderRepository repository, Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        transaction = new TransactionTemplate(transactionManager);
    }

    public List<OrderResponse> list(long userId) {
        return translateBusy(() -> repository.findAll(userId).stream().map(OrderResponse::from).toList());
    }

    public OrderResponse get(long id, long userId) {
        return translateBusy(() -> repository.findById(id, userId)
                .map(OrderResponse::from).orElseThrow(OrderNotFoundException::new));
    }

    public OrderResponse updateStatus(long id, long userId, StatusUpdateRequest request) {
        return translateBusy(() -> transaction.execute(transactionStatus -> {
                var observed = repository.findById(id, userId).orElseThrow(OrderNotFoundException::new);
                if (observed.version() != request.expectedVersion()) {
                    throw new OrderVersionConflictException();
                }
                var result = OrderStatusTransitions.classify(observed.status(), request.status());
                if (result == OrderStatusTransitions.Result.NO_OP) {
                    return OrderResponse.from(observed);
                }
                if (result == OrderStatusTransitions.Result.FORBIDDEN) {
                    throw new InvalidStatusTransitionException();
                }
                var updated = repository.updateStatus(id, userId, observed.version(), observed.status(), request.status());
                if (updated == 0) {
                    // A releitura apenas classifica a falha; nunca reaplica a intenção.
                    repository.findById(id, userId).orElseThrow(OrderNotFoundException::new);
                    throw new OrderVersionConflictException();
                }
                if (updated != 1) {
                    throw new IllegalStateException("Quantidade inesperada de pedidos alterados.");
                }
                return repository.findById(id, userId).map(OrderResponse::from).orElseThrow(OrderNotFoundException::new);
            }));
    }

    public OrderResponse create(long userId, CreateOrderRequest request) {
        var createdAt = Instant.now(clock);
        return translateBusy(() -> {
            var order = transaction.execute(status -> repository.insert(userId, request, createdAt));
            return OrderResponse.from(order);
        });
    }

    private static <T> T translateBusy(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLiteException sqlite && (sqlite.getResultCode().code & 0xff) == 5) {
                    throw new DatabaseBusyException(failure);
                }
            }
            throw failure;
        }
    }
}
