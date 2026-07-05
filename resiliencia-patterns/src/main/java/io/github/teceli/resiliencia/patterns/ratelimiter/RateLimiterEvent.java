package io.github.teceli.resiliencia.patterns.ratelimiter;

import io.github.teceli.resiliencia.core.spi.ResilienceEvent;

import java.time.Duration;
import java.time.Instant;

/**
 * Events emitted by the RateLimiter pattern.
 */
public sealed interface RateLimiterEvent extends ResilienceEvent {
    @Override
    default String patternName() {
        return "rate-limiter";
    }

    record Permitted(Instant timestamp, int remainingPermits) implements RateLimiterEvent {}

    record Rejected(Instant timestamp, Duration estimatedWait) implements RateLimiterEvent {}
}
