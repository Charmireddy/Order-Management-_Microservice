package com.orderservice.client;

import com.orderservice.dto.OrderDto;
import com.orderservice.exception.PaymentServiceException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.UUID;

/**
 * Client for the downstream PaymentService.
 *
 * Circuit Breaker config (from application.yml):
 *   slidingWindowSize=10, failureRateThreshold=50%, waitDurationInOpenState=10s
 *
 * States:
 *   CLOSED  → normal operation, calls go through
 *   OPEN    → circuit tripped, fallback fires immediately (no downstream call)
 *   HALF-OPEN → 3 probe calls decide whether to close or re-open
 */
@Component
@Slf4j
public class PaymentServiceClient {

    private final WebClient webClient;

    @Value("${payment.service.timeout:3000}")
    private long timeoutMs;

    public PaymentServiceClient(@Value("${payment.service.url:http://localhost:9090}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Process a payment.  Wraps the outbound HTTP call in:
     *   1. @Retry(3 attempts, 500ms back-off)
     *   2. @CircuitBreaker with a fallback that returns a DEGRADED response
     *      instead of letting the failure propagate to the caller.
     */
    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    @Retry(name = "paymentService")
    public OrderDto.PaymentResponse processPayment(OrderDto.PaymentRequest request) {
        log.info("Calling PaymentService for orderId={}", request.getOrderId());

        return webClient.post()
                .uri("/api/v1/payments")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OrderDto.PaymentResponse.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .block();
    }

    /**
     * Fallback — invoked when the circuit is OPEN or all retries are exhausted.
     *
     * This is the key to the 97% availability claim:
     *   - Catalog/order service stays up
     *   - Order is saved as PAYMENT_FAILED and can be retried asynchronously
     *   - User gets a meaningful response instead of a 500
     */
    @SuppressWarnings("unused")
    private OrderDto.PaymentResponse paymentFallback(OrderDto.PaymentRequest request, Throwable ex) {
        log.warn("PaymentService circuit OPEN or call failed for orderId={}, reason={}",
                request.getOrderId(), ex.getMessage());

        // Return a graceful degraded response — order stays in PAYMENT_FAILED state
        return OrderDto.PaymentResponse.builder()
                .paymentId("FALLBACK-" + UUID.randomUUID())
                .status("FAILED")
                .message("Payment service is temporarily unavailable. Order queued for retry.")
                .build();
    }
}
