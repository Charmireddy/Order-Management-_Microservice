package com.orderservice.controller;

import com.orderservice.dto.OrderDto;
import com.orderservice.exception.DuplicateOrderException;
import com.orderservice.service.OrderService;
import com.orderservice.service.RateLimitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for /api/v1/orders
 *
 * API versioning strategy: URI versioning (/api/v1/).
 * When v2 is needed, introduce /api/v2/orders without breaking v1 clients.
 *
 * Rate limit: 10 req/s per user, enforced via Redis token bucket.
 * Idempotency: Idempotency-Key header prevents duplicate order creation.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final RateLimitService rateLimitService;

    // ── POST /api/v1/orders ──────────────────────────────────────────────────
    //   201 Created on success
    //   409 Conflict if idempotency key was already used
    //   429 Too Many Requests if rate limit exceeded
    //   400 Bad Request on validation failure

    @PostMapping
    public ResponseEntity<OrderDto.ApiResponse<OrderDto.OrderResponse>> createOrder(
            @Valid @RequestBody OrderDto.CreateOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        // ── Rate limiting ────────────────────────────────────────────────────
        String rateLimitUserId = userId != null ? userId : request.getUserId();
        if (!rateLimitService.isAllowed(rateLimitUserId)) {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Retry-After", String.valueOf(rateLimitService.getRetryAfterSeconds()));
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .headers(headers)
                    .body(OrderDto.ApiResponse.error(
                            "Rate limit exceeded. Max 10 requests/second.", "RATE_LIMIT_EXCEEDED"));
        }

        // ── Validate idempotency key format if provided ──────────────────────
        if (idempotencyKey != null && !idempotencyKey.matches(
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
            return ResponseEntity.badRequest()
                    .body(OrderDto.ApiResponse.error(
                            "Idempotency-Key must be a valid UUID", "INVALID_IDEMPOTENCY_KEY"));
        }

        try {
            OrderDto.OrderResponse order = orderService.createOrder(request, idempotencyKey);

            HttpHeaders headers = new HttpHeaders();
            headers.add("Location", "/api/v1/orders/" + order.getId());

            return ResponseEntity.status(HttpStatus.CREATED)
                    .headers(headers)
                    .body(OrderDto.ApiResponse.ok(order, "Order created successfully"));

        } catch (DuplicateOrderException ex) {
            // Idempotency key already used — return the cached response with 409
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(OrderDto.ApiResponse.<OrderDto.OrderResponse>builder()
                            .success(true)   // it's a success — just a repeat
                            .message("Duplicate request — returning original response")
                            .data(ex.getCachedResponse())
                            .build());
        }
    }

    // ── GET /api/v1/orders/{orderId} ─────────────────────────────────────────
    //   200 OK
    //   404 Not Found

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDto.ApiResponse<OrderDto.OrderResponse>> getOrder(
            @PathVariable UUID orderId) {

        OrderDto.OrderResponse order = orderService.getOrder(orderId);
        return ResponseEntity.ok(OrderDto.ApiResponse.ok(order));
    }

    // ── GET /api/v1/orders?userId=xxx ────────────────────────────────────────
    //   200 OK (empty list if no orders)

    @GetMapping
    public ResponseEntity<OrderDto.ApiResponse<List<OrderDto.OrderResponse>>> getOrdersByUser(
            @RequestParam String userId) {

        List<OrderDto.OrderResponse> orders = orderService.getOrdersByUser(userId);
        return ResponseEntity.ok(OrderDto.ApiResponse.ok(orders));
    }

    // ── DELETE /api/v1/orders/{orderId} ─────────────────────────────────────
    //   204 No Content  (DELETE is idempotent by definition — HTTP spec)

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> cancelOrder(@PathVariable UUID orderId) {
        orderService.cancelOrder(orderId);
        return ResponseEntity.noContent().build();
    }

    // ── Health/info endpoint ─────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<OrderDto.ApiResponse<String>> health() {
        return ResponseEntity.ok(OrderDto.ApiResponse.ok("Order service is running"));
    }
}
