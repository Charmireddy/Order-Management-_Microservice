package com.orderservice.service;

import com.orderservice.dto.OrderDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Idempotency guard for POST /orders.
 *
 * Flow:
 *   1. Client sends Idempotency-Key: <UUID> header
 *   2. Before processing, check Redis for that key
 *   3. Key EXISTS  → return the cached OrderResponse immediately (409 Conflict)
 *   4. Key MISSING → process the order, store result in Redis with 24-hour TTL
 *
 * Why it matters:
 *   User clicks "Place Order" twice due to slow 4G.
 *   Without idempotency → double charge.
 *   With idempotency    → second request returns the first order, no duplicate.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:order:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${idempotency.ttl-seconds:86400}")
    private long ttlSeconds;

    /**
     * Check whether a response for this idempotency key is already cached.
     */
    public Optional<OrderDto.OrderResponse> getCachedResponse(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        String cached = redisTemplate.opsForValue().get(redisKey);

        if (cached == null) {
            return Optional.empty();
        }

        try {
            OrderDto.OrderResponse response = objectMapper.readValue(cached, OrderDto.OrderResponse.class);
            log.info("Idempotency cache HIT for key={}", idempotencyKey);
            return Optional.of(response);
        } catch (Exception e) {
            log.error("Failed to deserialize cached response for key={}", idempotencyKey, e);
            return Optional.empty();
        }
    }

    /**
     * Store the response in Redis with the configured TTL (default 24 hours).
     */
    public void storeResponse(String idempotencyKey, OrderDto.OrderResponse response) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        try {
            String value = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(redisKey, value, Duration.ofSeconds(ttlSeconds));
            log.info("Stored idempotency key={} in Redis, TTL={}s", idempotencyKey, ttlSeconds);
        } catch (Exception e) {
            log.error("Failed to store idempotency key={} in Redis", idempotencyKey, e);
        }
    }

    /**
     * Mark a key as "in-flight" to prevent concurrent duplicate requests
     * from both processing simultaneously (optimistic lock via NX).
     *
     * Returns true if the lock was acquired (first caller), false if another
     * request is already processing the same idempotency key.
     */
    public boolean acquireProcessingLock(String idempotencyKey) {
        String lockKey = KEY_PREFIX + "lock:" + idempotencyKey;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "PROCESSING", Duration.ofSeconds(30));
        return Boolean.TRUE.equals(acquired);
    }

    public void releaseProcessingLock(String idempotencyKey) {
        redisTemplate.delete(KEY_PREFIX + "lock:" + idempotencyKey);
    }
}
