package io.github.teceli.resiliencia.metrics;

import io.github.teceli.resiliencia.metrics.bulkhead.BulkheadSnapshot;
import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerSnapshot;
import io.github.teceli.resiliencia.metrics.ratelimiter.RateLimiterSnapshot;

/**
 * Gauge-worthy live state emitted by a pattern. Retry and Timeout have no live state (no window,
 * no permit count), so they never produce a {@code Snapshot}, only {@link Counters}.
 */
public sealed interface Snapshot permits CircuitBreakerSnapshot, BulkheadSnapshot, RateLimiterSnapshot {
}
