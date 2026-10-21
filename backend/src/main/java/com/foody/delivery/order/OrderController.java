package com.foody.delivery.order;

import java.net.URI;
import java.util.List;

import com.foody.delivery.order.dto.CreateOrderRequest;
import com.foody.delivery.order.dto.OrderResponse;
import com.foody.delivery.order.dto.StatusUpdateRequest;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping
    public List<OrderResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable("id") long id) {
        if (id <= 0) throw new InvalidOrderIdException();
        return service.get(id);
    }

    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(@PathVariable("id") long id, @Valid @RequestBody StatusUpdateRequest request) {
        if (id <= 0) throw new InvalidOrderIdException();
        return service.updateStatus(id, request);
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        var response = service.create(request);
        return ResponseEntity.created(URI.create("/api/orders/" + response.id())).body(response);
    }
}
