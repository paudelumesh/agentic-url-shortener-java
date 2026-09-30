package com.agentic.urlshortener.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    @Test
    void allowsRequestsWithinCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 0.0);
        assertTrue(limiter.allow("client-a"));
        assertTrue(limiter.allow("client-a"));
        assertTrue(limiter.allow("client-a"));
    }

    @Test
    void blocksRequestsBeyondCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 0.0);
        limiter.allow("client-a");
        limiter.allow("client-a");
        assertFalse(limiter.allow("client-a"));
    }

    @Test
    void keysAreIndependent() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 0.0);
        assertTrue(limiter.allow("client-a"));
        assertTrue(limiter.allow("client-b"));
    }

    @Test
    void refillsOverTime() {
        AtomicLong clock = new AtomicLong(0L);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1.0, clock::get);
        assertTrue(limiter.allow("client-a"));
        assertFalse(limiter.allow("client-a"));
        clock.set(1_000_000_000L);
        assertTrue(limiter.allow("client-a"));
    }
}
