package io.github.teceli.resiliencia.core.spi;

import io.github.teceli.resiliencia.core.internal.SystemClock;

import java.time.Instant;

/**
 * Abstraction over time, used by patterns that need to read the current instant or wait
 * (Retry backoff, CircuitBreaker open-state duration, RateLimiter windows, ...).
 * Extension point: test code can supply a manual/virtual implementation instead of
 * {@link #systemClock()} to make timing-based tests deterministic and instant.
 */
public interface Clock {
    /**
     * The current instant, as seen by this clock.
     */
    Instant instant();

    /**
     * Suspend the calling thread for the given duration, as measured by this clock.
     * Deliberately mirrors {@link Thread#sleep(long)}'s checked {@link InterruptedException}
     * rather than wrapping it in a resiliencia exception: callers already have an established,
     * idiomatic interruption protocol (catch, restore the interrupt flag, react) to follow here,
     * and every caller in this codebase does exactly that at the call site.
     */
    void sleep(long millis) throws InterruptedException;

    /**
     * The default clock, backed by the system wall clock and {@link Thread#sleep(long)}.
     */
    static Clock systemClock() {
        return SystemClock.INSTANCE;
    }
}
