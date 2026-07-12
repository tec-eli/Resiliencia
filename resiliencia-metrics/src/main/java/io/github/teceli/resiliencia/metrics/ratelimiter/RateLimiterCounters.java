package io.github.teceli.resiliencia.metrics.ratelimiter;

import io.github.teceli.resiliencia.metrics.Counters;

/**
 * Counter/timer-worthy occurrences emitted by a RateLimiter.
 */
public sealed interface RateLimiterCounters extends Counters {

    enum Outcome {
        PERMITTED,
        REJECTED
    }

    record Call(String name, Outcome outcome) implements RateLimiterCounters {
    }
}
