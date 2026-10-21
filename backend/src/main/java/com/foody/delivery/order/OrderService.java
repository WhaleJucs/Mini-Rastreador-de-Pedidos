package com.foody.delivery.order;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.foody.delivery.auth.DatabaseBusyException;
import com.foody.delivery.order.dto.CreateOrderRequest;
import com.foody.delivery.order.dto.OrderResponse;
import com.foody.delivery.order.dto.StatusUpdateRequest;

import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
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

    public List<OrderResponse> list() {
        try {
            return repository.findAll().stream().map(OrderResponse::from).toList();
        } catch (DataAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLiteException sqlite && (sqlite.getResultCode().code & 0xff) == 5) {
                    throw new DatabaseBusyException(failure);
                }
            }
            throw failure;
        }
    }

    public OrderResponse get(long id) {
        try {
            return repository.findById(id).map(OrderResponse::from).orElseThrow(OrderNotFoundException::new);
        } catch (DataAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLiteException sqlite && (sqlite.getResultCode().code & 0xff) == 5) {
                    throw new DatabaseBusyException(failure);
                }
            }
            throw failure;
        }
    }

    public OrderResponse updateStatus(long id, StatusUpdateRequest request) {
        try {
            return transaction.execute(transactionStatus -> {
                var observed = repository.findById(id).orElseThrow(OrderNotFoundException::new);
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
                var updated = repository.updateStatus(id, observed.version(), observed.status(), request.status());
                if (updated == 0) {
                    // A releitura apenas classifica a falha; nunca reaplica a intenção.
                    repository.findById(id).orElseThrow(OrderNotFoundException::new);
                    throw new OrderVersionConflictException();
                }
                if (updated != 1) {
                    throw new IllegalStateException("Quantidade inesperada de pedidos alterados.");
                }
                return repository.findById(id).map(OrderResponse::from).orElseThrow(OrderNotFoundException::new);
            });
        } catch (RuntimeException failure) {
            // TransactionTemplate conclui rollback/cleanup antes de traduzir a contenção.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLiteException sqlite && (sqlite.getResultCode().code & 0xff) == 5) {
                    throw new DatabaseBusyException(failure);
                }
            }
            throw failure;
        }
    }

    public OrderResponse create(CreateOrderRequest request) {
        var createdAt = Instant.now(clock);
        try {
            var order = transaction.execute(status -> repository.insert(request, createdAt));
            return OrderResponse.from(order);
        } catch (RuntimeException failure) {
            // Checa se a exceção é causada por um banco de dados ocupado (SQLite busy)
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLiteException sqlite && (sqlite.getResultCode().code & 0xff) == 5) {
                    throw new DatabaseBusyException(failure);
                }
            }
            throw failure;
        }
    }
}
