// backend/src/main/java/com/meetsphere/util/SlidingWindowRateLimiter.java
package com.meetsphere.util;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Allows at most {@code maxPermits} actions per key within a sliding time window.
 * Used so that the invitation endpoint cannot be abused to send unlimited email.
 *
 * In-memory, so the limit is per application instance (fine for this project;
 * a multi-instance deployment would need a shared store such as Redis).
 */
public class SlidingWindowRateLimiter {

    private final int maxPermits;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<Object, ArrayDeque<Long>> events = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int maxPermits, long windowMillis) {
        this(maxPermits, windowMillis, System::currentTimeMillis);
    }

    /** The clock is injectable so tests can move time forward without sleeping. */
    public SlidingWindowRateLimiter(int maxPermits, long windowMillis, LongSupplier clock) {
        this.maxPermits = maxPermits;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /** Tries to use {@code permits} units for {@code key}; returns false (and uses nothing) if over the limit. */
    public boolean tryAcquire(Object key, int permits) {
        if (permits > maxPermits) return false;
        ArrayDeque<Long> queue = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (queue) {
            long now = clock.getAsLong();
            while (!queue.isEmpty() && now - queue.peekFirst() >= windowMillis) queue.pollFirst();
            if (queue.size() + permits > maxPermits) return false;
            for (int i = 0; i < permits; i++) queue.addLast(now);
            return true;
        }
    }
}
