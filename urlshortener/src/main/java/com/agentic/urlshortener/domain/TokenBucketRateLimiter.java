package com.agentic.urlshortener.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public class TokenBucketRateLimiter {
    private final int capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new HashMap<>();

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;
    }

    public TokenBucketRateLimiter(int capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    TokenBucketRateLimiter(int capacity, double refillPerSecond, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoClock = nanoClock;
    }

    public synchronized boolean allow(String key) {
        long now = nanoClock.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(key, k -> {
            Bucket b = new Bucket();
            b.tokens = capacity;
            b.lastRefillNanos = now;
            return b;
        });
        double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0;
        bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSecond);
        bucket.lastRefillNanos = now;
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0;
            return true;
        }
        return false;
    }
}
