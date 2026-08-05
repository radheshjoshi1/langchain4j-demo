package org.example.util;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding-window rate limiter: blocks the calling thread in {@link #acquire()} until issuing
 * another call would keep the count within {@code maxCalls} over the trailing {@code window}.
 */
public class RateLimiter {

    private final int maxCalls;
    private final Duration window;
    private final Deque<Instant> callTimestamps = new ArrayDeque<>();

    public RateLimiter(int maxCalls, Duration window) {
        this.maxCalls = maxCalls;
        this.window = window;
    }

    public synchronized void acquire() {
        while (true) {
            Instant now = Instant.now();
            Instant windowStart = now.minus(window);
            while (!callTimestamps.isEmpty() && callTimestamps.peekFirst().isBefore(windowStart)) {
                callTimestamps.pollFirst();
            }
            if (callTimestamps.size() < maxCalls) {
                callTimestamps.addLast(now);
                return;
            }
            long waitMillis = Duration.between(now, callTimestamps.peekFirst().plus(window)).toMillis();
            try {
                Thread.sleep(Math.max(1, waitMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
