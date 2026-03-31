package com.orderservice.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Demo endpoints to observe Circuit Breaker state in real time.
 *
 * Useful during interviews to SHOW the pattern, not just describe it.
 *
 * GET  /api/v1/demo/circuit-breaker/state   → current state + metrics
 * POST /api/v1/demo/circuit-breaker/force   → force state (OPEN/CLOSED/HALF_OPEN)
 */
@RestController
@RequestMapping("/api/v1/demo/circuit-breaker")
@RequiredArgsConstructor
public class CircuitBreakerDemoController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> getState() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("paymentService");
        CircuitBreaker.Metrics metrics = cb.getMetrics();

        return ResponseEntity.ok(Map.of(
                "name", cb.getName(),
                "state", cb.getState().name(),
                "failureRate", metrics.getFailureRate() + "%",
                "bufferedCalls", metrics.getNumberOfBufferedCalls(),
                "failedCalls", metrics.getNumberOfFailedCalls(),
                "successCalls", metrics.getNumberOfSuccessfulCalls(),
                "notPermittedCalls", metrics.getNumberOfNotPermittedCalls(),
                "description", getStateDescription(cb.getState())
        ));
    }

    @PostMapping("/force/{state}")
    public ResponseEntity<Map<String, String>> forceState(@PathVariable String state) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("paymentService");

        switch (state.toUpperCase()) {
            case "OPEN"      -> cb.transitionToOpenState();
            case "CLOSED"    -> cb.transitionToClosedState();
            case "HALF_OPEN" -> cb.transitionToHalfOpenState();
            default -> {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Valid states: OPEN, CLOSED, HALF_OPEN"));
            }
        }

        return ResponseEntity.ok(Map.of(
                "message", "Circuit breaker forced to " + state.toUpperCase(),
                "currentState", cb.getState().name()
        ));
    }

    private String getStateDescription(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED    -> "Normal operation — all calls pass through";
            case OPEN      -> "Circuit tripped — fallback fires immediately, no downstream calls";
            case HALF_OPEN -> "Testing recovery — limited probe calls allowed";
            default        -> state.name();
        };
    }
}
