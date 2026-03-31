package com.orderservice.exception;

import com.orderservice.dto.OrderDto;
import lombok.Getter;

@Getter
public class DuplicateOrderException extends RuntimeException {

    private final String idempotencyKey;
    private final OrderDto.OrderResponse cachedResponse;

    public DuplicateOrderException(String idempotencyKey, OrderDto.OrderResponse cachedResponse) {
        super("Duplicate request for idempotency key: " + idempotencyKey);
        this.idempotencyKey = idempotencyKey;
        this.cachedResponse = cachedResponse;
    }
}
