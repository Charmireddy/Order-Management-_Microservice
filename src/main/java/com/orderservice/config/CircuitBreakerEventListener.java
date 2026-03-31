package com.orderservice.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Listens to circuit breaker state transitions and logs them.
 *
 * For interviews: this is how you'd hook in alerting (PagerDuty, Slack)
 * when the circuit opens, signalling a downstream degradation.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CircuitBreakerEventListener {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @PostConstruct
    public void registerListeners() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("paymentService");

        cb.getEventPublisher()
                .onStateTransition(event -> {
                    log.warn("╔══ CIRCUIT BREAKER STATE CHANGE ══════════════════╗");
                    log.warn("║  {} → {}",
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState());
                    log.warn("╚══════════════════════════════════════════════════╝");

                    // In production: fire PagerDuty alert when → OPEN
                })
                .onCallNotPermitted(event ->
                        log.warn("Circuit OPEN — call blocked for paymentService"))
                .onError(event ->
                        log.error("Payment call failed: {}", event.getThrowable().getMessage()))
                .onSuccess(event ->
                        log.debug("Payment call succeeded in {}ms",
                                event.getElapsedDuration().toMillis()));
    }
}
