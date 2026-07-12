package io.github.teceli.resiliencia.metrics;

import io.github.teceli.resiliencia.metrics.bulkhead.BulkheadCounters;
import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerCounters;
import io.github.teceli.resiliencia.metrics.policy.PolicyCounters;
import io.github.teceli.resiliencia.metrics.ratelimiter.RateLimiterCounters;
import io.github.teceli.resiliencia.metrics.retry.RetryCounters;
import io.github.teceli.resiliencia.metrics.timeout.TimeoutCounters;

/**
 * Counter/timer-worthy occurrences emitted by a pattern or by Policy's order validation.
 */
public sealed interface Counters permits
    RetryCounters, TimeoutCounters, CircuitBreakerCounters, BulkheadCounters, RateLimiterCounters, PolicyCounters {
}
