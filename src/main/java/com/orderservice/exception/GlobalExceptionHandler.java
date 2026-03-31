package com.orderservice.exception;

import com.orderservice.dto.OrderDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralised error handler.
 *
 * HTTP status code guide (memorise for interviews):
 *   400 Bad Request       — validation failure, malformed input
 *   401 Unauthorized      — missing/invalid JWT
 *   403 Forbidden         — authenticated but RBAC denied
 *   404 Not Found         — resource doesn't exist
 *   409 Conflict          — duplicate / idempotency key already used
 *   429 Too Many Requests — rate limit exceeded (handled in controller)
 *   503 Service Unavailable — circuit breaker OPEN
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── 400 ─────────────────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<OrderDto.ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }

        return ResponseEntity.badRequest()
                .body(OrderDto.ApiResponse.<Void>builder()
                        .success(false)
                        .error(OrderDto.ErrorDetails.builder()
                                .code("VALIDATION_FAILED")
                                .message("Request validation failed")
                                .details(fieldErrors)
                                .build())
                        .build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<OrderDto.ApiResponse<Void>> handleIllegalArgument(
            IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(OrderDto.ApiResponse.error(ex.getMessage(), "BAD_REQUEST"));
    }

    // ── 404 ─────────────────────────────────────────────────────────────────

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<OrderDto.ApiResponse<Void>> handleOrderNotFound(
            OrderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(OrderDto.ApiResponse.error(ex.getMessage(), "ORDER_NOT_FOUND"));
    }

    // ── 409 ─────────────────────────────────────────────────────────────────

    @ExceptionHandler(DuplicateOrderException.class)
    public ResponseEntity<OrderDto.ApiResponse<Object>> handleDuplicate(
            DuplicateOrderException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(OrderDto.ApiResponse.<Object>builder()
                        .success(true)
                        .message("Duplicate request — returning original response")
                        .data(ex.getCachedResponse())
                        .build());
    }

    // ── 422 Unprocessable Entity ─────────────────────────────────────────────

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<OrderDto.ApiResponse<Void>> handleIllegalState(
            IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(OrderDto.ApiResponse.error(ex.getMessage(), "INVALID_STATE_TRANSITION"));
    }

    // ── 503 Service Unavailable ──────────────────────────────────────────────

    @ExceptionHandler(PaymentServiceException.class)
    public ResponseEntity<OrderDto.ApiResponse<Void>> handlePaymentServiceDown(
            PaymentServiceException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(OrderDto.ApiResponse.error(
                        "Payment service is temporarily unavailable", "CIRCUIT_BREAKER_OPEN"));
    }

    // ── 500 ─────────────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OrderDto.ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(OrderDto.ApiResponse.error("An unexpected error occurred", "INTERNAL_ERROR"));
    }
}
