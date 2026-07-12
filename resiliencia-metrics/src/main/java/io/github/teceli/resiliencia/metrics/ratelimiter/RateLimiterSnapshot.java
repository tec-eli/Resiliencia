package io.github.teceli.resiliencia.metrics.ratelimiter;

import io.github.teceli.resiliencia.metrics.Snapshot;

/**
 * Gauge-worthy live state of a RateLimiter.
 */
public sealed interface RateLimiterSnapshot extends Snapshot {

    record RemainingPermits(String name, int remaining) implements RateLimiterSnapshot {
    }
}
