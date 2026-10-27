package com.meetsphere.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowRateLimiterTest {

    @Test
    void allowsUpToTheLimitThenBlocks() {
        AtomicLong now = new AtomicLong(0);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5, 1000, now::get);
        assertTrue(limiter.tryAcquire(1L, 3));
        assertTrue(limiter.tryAcquire(1L, 2));
        assertFalse(limiter.tryAcquire(1L, 1));
    }

    @Test
    void blockedRequestUsesNothing() {
        AtomicLong now = new AtomicLong(0);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5, 1000, now::get);
        assertTrue(limiter.tryAcquire(1L, 4));
        assertFalse(limiter.tryAcquire(1L, 2));      // would exceed → rejected, nothing consumed
        assertTrue(limiter.tryAcquire(1L, 1));       // the remaining 1 is still available
    }

    @Test
    void allowsAgainWhenTheWindowHasPassed() {
        AtomicLong now = new AtomicLong(0);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(2, 1000, now::get);
        assertTrue(limiter.tryAcquire("u", 2));
        assertFalse(limiter.tryAcquire("u", 1));
        now.set(1000);
        assertTrue(limiter.tryAcquire("u", 2));
    }

    @Test
    void keysAreIndependent() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1, 1000, () -> 0L);
        assertTrue(limiter.tryAcquire(1L, 1));
        assertTrue(limiter.tryAcquire(2L, 1));
        assertFalse(limiter.tryAcquire(1L, 1));
    }

    @Test
    void requestLargerThanTheLimitIsAlwaysRejected() {
        assertFalse(new SlidingWindowRateLimiter(3, 1000, () -> 0L).tryAcquire(1L, 4));
    }
}
