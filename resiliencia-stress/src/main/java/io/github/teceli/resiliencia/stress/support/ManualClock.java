package io.github.teceli.resiliencia.stress.support;

import io.github.teceli.resiliencia.core.spi.Clock;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic {@link Clock} for jcstress scenarios: time only moves when advanced explicitly
 * via {@link #advance}, so a pattern's wait-duration or window logic can be pushed past a
 * threshold before actors start racing, instead of relying on real-time sleeps. Meant to be set
 * up single-threaded in a {@code @State} constructor, before jcstress actors run concurrently.
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
