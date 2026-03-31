package com.orderservice.service;

import com.orderservice.client.PaymentServiceClient;
import com.orderservice.dto.OrderDto;
import com.orderservice.exception.DuplicateOrderException;
import com.orderservice.exception.OrderNotFoundException;
import com.orderservice.model.Order;
import com.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final PaymentServiceClient paymentServiceClient;
    private final IdempotencyService idempotencyService;

    /**
     * Create a new order.
     *
     * Idempotency contract:
     *   - If idempotencyKey is provided and a cached response exists → return it (409)
     *   - Otherwise create the order, call payment, persist, cache the result
     */
    @Transactional
    public OrderDto.OrderResponse createOrder(OrderDto.CreateOrderRequest request,
                                               String idempotencyKey) {
        // ── 1. Idempotency check ────────────────────────────────────────────
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<OrderDto.OrderResponse> cached = idempotencyService.getCachedResponse(idempotencyKey);
            if (cached.isPresent()) {
                log.info("Returning cached response for idempotencyKey={}", idempotencyKey);
                throw new DuplicateOrderException(idempotencyKey, cached.get());
            }

            // Acquire a processing lock to prevent concurrent duplicate requests
            if (!idempotencyService.acquireProcessingLock(idempotencyKey)) {
                throw new DuplicateOrderException(idempotencyKey, null);
            }
        }

        try {
            // ── 2. Persist order in PENDING state ───────────────────────────
            Order order = Order.builder()
                    .userId(request.getUserId())
                    .productId(request.getProductId())
                    .quantity(request.getQuantity())
                    .totalAmount(request.getTotalAmount())
                    .status(Order.OrderStatus.PENDING)
                    .idempotencyKey(idempotencyKey)
                    .build();

            order = orderRepository.save(order);
            log.info("Order created orderId={} status=PENDING", order.getId());

            // ── 3. Call PaymentService (circuit breaker + retry applied) ────
            order.setStatus(Order.OrderStatus.PAYMENT_PROCESSING);
            order = orderRepository.save(order);

            OrderDto.PaymentResponse payment = paymentServiceClient.processPayment(
                    OrderDto.PaymentRequest.builder()
                            .orderId(order.getId().toString())
                            .userId(order.getUserId())
                            .amount(order.getTotalAmount())
                            .build()
            );

            // ── 4. Update order based on payment outcome ────────────────────
            if ("SUCCESS".equalsIgnoreCase(payment.getStatus())) {
                order.setStatus(Order.OrderStatus.CONFIRMED);
                order.setPaymentId(payment.getPaymentId());
            } else {
                // Fallback fired — circuit was OPEN or payment failed
                order.setStatus(Order.OrderStatus.PAYMENT_FAILED);
                order.setPaymentId(payment.getPaymentId());
                log.warn("Payment failed/fallback for orderId={}: {}", order.getId(), payment.getMessage());
            }

            order = orderRepository.save(order);
            log.info("Order finalized orderId={} status={}", order.getId(), order.getStatus());

            // ── 5. Build response and cache for idempotency ─────────────────
            OrderDto.OrderResponse response = toResponse(order);
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.storeResponse(idempotencyKey, response);
            }

            return response;

        } finally {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                idempotencyService.releaseProcessingLock(idempotencyKey);
            }
        }
    }

    public OrderDto.OrderResponse getOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return toResponse(order);
    }

    public List<OrderDto.OrderResponse> getOrdersByUser(String userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public OrderDto.OrderResponse cancelOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() == Order.OrderStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot cancel a confirmed order");
        }

        order.setStatus(Order.OrderStatus.CANCELLED);
        order = orderRepository.save(order);
        log.info("Order cancelled orderId={}", orderId);
        return toResponse(order);
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    private OrderDto.OrderResponse toResponse(Order order) {
        return OrderDto.OrderResponse.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .productId(order.getProductId())
                .quantity(order.getQuantity())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .paymentId(order.getPaymentId())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
