package io.github.teceli.resiliencia.test;

import io.github.teceli.resiliencia.core.spi.Clock;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic {@link Clock} for tests: time only moves when advanced explicitly via
 * {@link #advance} or when a pattern sleeps, so timing-based behavior (Retry backoff,
 * RateLimiter windows) can be asserted exactly and runs instantly.
 *
 * Thread-safe: patterns may read and sleep on this clock from multiple threads.
 */
public final class ManualClock implements Clock {

    private static final Instant DEFAULT_START = Instant.parse("2020-01-01T00:00:00Z");

    private Instant now;

    private ManualClock(Instant start) {
        this.now = start;
    }

    /**
     * A manual clock starting at a fixed, arbitrary instant.
     */
    public static ManualClock create() {
        return new ManualClock(DEFAULT_START);
    }

    /**
     * A manual clock starting at the given instant.
     */
    public static ManualClock startingAt(Instant start) {
        Objects.requireNonNull(start, "start must not be null");
        return new ManualClock(start);
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }

    /**
     * Does not block: advances the clock by the requested duration and returns immediately.
     */
    @Override
    public synchronized void sleep(long millis) {
        now = now.plusMillis(millis);
    }

    /**
     * Move the clock forward by the given duration.
     */
    public synchronized void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        now = now.plus(duration);
    }
}
