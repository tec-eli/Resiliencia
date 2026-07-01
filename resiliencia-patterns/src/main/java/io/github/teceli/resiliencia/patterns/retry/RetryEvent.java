package io.github.teceli.resiliencia.patterns.retry;

import io.github.teceli.resiliencia.core.spi.ResilienceEvent;

import java.time.Instant;

/**
 * Events emitted by the Retry pattern.
 */
public sealed interface RetryEvent extends ResilienceEvent {
    @Override
    default String patternName() {
        return "retry";
    }

    record AttemptFailed(Instant timestamp, int attemptNumber, Throwable error) implements RetryEvent {}

    record Success(Instant timestamp, int totalAttempts) implements RetryEvent {}

    record Exhausted(Instant timestamp, int totalAttempts, Throwable lastError) implements RetryEvent {}
}
