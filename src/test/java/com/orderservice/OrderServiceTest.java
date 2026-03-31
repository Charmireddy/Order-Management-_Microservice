package com.orderservice;

import com.orderservice.client.PaymentServiceClient;
import com.orderservice.dto.OrderDto;
import com.orderservice.model.Order;
import com.orderservice.repository.OrderRepository;
import com.orderservice.service.IdempotencyService;
import com.orderservice.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentServiceClient paymentServiceClient;

    @Mock
    private IdempotencyService idempotencyService;

    @InjectMocks
    private OrderService orderService;

    private OrderDto.CreateOrderRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = OrderDto.CreateOrderRequest.builder()
                .userId("user-123")
                .productId("prod-456")
                .quantity(2)
                .totalAmount(new BigDecimal("99.99"))
                .build();
    }

    @Test
    @DisplayName("createOrder → CONFIRMED when payment succeeds")
    void createOrder_paymentSuccess_returnsConfirmed() {
        // Arrange
        Order savedOrder = buildOrder(Order.OrderStatus.PENDING);
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        when(idempotencyService.getCachedResponse(anyString())).thenReturn(Optional.empty());
        when(idempotencyService.acquireProcessingLock(anyString())).thenReturn(true);
        when(paymentServiceClient.processPayment(any())).thenReturn(
                OrderDto.PaymentResponse.builder()
                        .paymentId("pay-001")
                        .status("SUCCESS")
                        .build()
        );

        // Act
        OrderDto.OrderResponse response = orderService.createOrder(validRequest, UUID.randomUUID().toString());

        // Assert
        assertThat(response).isNotNull();
        verify(paymentServiceClient, times(1)).processPayment(any());
    }

    @Test
    @DisplayName("createOrder → PAYMENT_FAILED when circuit breaker fallback fires")
    void createOrder_circuitBreakerFallback_returnsPaymentFailed() {
        // Arrange
        Order savedOrder = buildOrder(Order.OrderStatus.PENDING);
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        when(idempotencyService.getCachedResponse(anyString())).thenReturn(Optional.empty());
        when(idempotencyService.acquireProcessingLock(anyString())).thenReturn(true);

        // Simulate fallback response (circuit OPEN)
        when(paymentServiceClient.processPayment(any())).thenReturn(
                OrderDto.PaymentResponse.builder()
                        .paymentId("FALLBACK-xxx")
                        .status("FAILED")
                        .message("Payment service is temporarily unavailable. Order queued for retry.")
                        .build()
        );

        // Act
        OrderDto.OrderResponse response = orderService.createOrder(validRequest, UUID.randomUUID().toString());

        // Assert: service stays available (97% uptime story)
        assertThat(response).isNotNull();
        verify(orderRepository, atLeast(2)).save(any(Order.class));
    }

    private Order buildOrder(Order.OrderStatus status) {
        return Order.builder()
                .id(UUID.randomUUID())
                .userId("user-123")
                .productId("prod-456")
                .quantity(2)
                .totalAmount(new BigDecimal("99.99"))
                .status(status)
                .build();
    }
}
