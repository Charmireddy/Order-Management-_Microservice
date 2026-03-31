package com.orderservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Token-bucket rate limiter backed by Redis.
 *
 * Per-user limit: 10 requests / second.
 * When exceeded → 429 Too Many Requests with Retry-After header.
 *
 * Why Redis (not in-memory)?
 *   Microservices run as multiple replicas behind a load balancer.
 *   In-memory rate limits are per-instance — user could hit 10×N requests.
 *   Redis ensures the limit is enforced globally across all pods.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RateLimitService {

    private static final String KEY_PREFIX = "ratelimit:user:";
    private static final int MAX_REQUESTS = 10;   // per window
    private static final long WINDOW_SECONDS = 1; // 1-second rolling window

    private final StringRedisTemplate redisTemplate;

    /**
     * Returns true if the request should be allowed.
     * Uses a simple counter with 1-second TTL as an approximation of
     * a fixed-window rate limiter.
     */
    public boolean isAllowed(String userId) {
        String key = KEY_PREFIX + userId + ":" + getCurrentWindowKey();

        Long count = redisTemplate.opsForValue().increment(key);

        if (count == 1) {
            // First request in this window — set expiry
            redisTemplate.expire(key, Duration.ofSeconds(WINDOW_SECONDS + 1));
        }

        boolean allowed = count != null && count <= MAX_REQUESTS;
        if (!allowed) {
            log.warn("Rate limit exceeded for userId={}, count={}", userId, count);
        }
        return allowed;
    }

    /**
     * Returns seconds until the current window resets (for Retry-After header).
     */
    public long getRetryAfterSeconds() {
        return WINDOW_SECONDS;
    }

    private String getCurrentWindowKey() {
        // Floor to the current second
        return String.valueOf(Instant.now().getEpochSecond());
    }
}
