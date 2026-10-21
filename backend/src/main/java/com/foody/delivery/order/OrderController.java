package com.foody.delivery.order;

import java.net.URI;
import java.util.List;

import com.foody.delivery.order.dto.CreateOrderRequest;
import com.foody.delivery.order.dto.OrderResponse;
import com.foody.delivery.order.dto.StatusUpdateRequest;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    public List<OrderResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable("id") long id, @AuthenticationPrincipal Jwt jwt) {
        if (id <= 0) throw new InvalidOrderIdException();
        return service.get(id, Long.parseLong(jwt.getSubject()));
    }

    @PatchMapping("/{id}/status")
    public OrderResponse updateStatus(@PathVariable("id") long id, @Valid @RequestBody StatusUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (id <= 0) throw new InvalidOrderIdException();
        return service.updateStatus(id, Long.parseLong(jwt.getSubject()), request);
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        var response = service.create(Long.parseLong(jwt.getSubject()), request);
        return ResponseEntity.created(URI.create("/api/orders/" + response.id())).body(response);
    }
}
