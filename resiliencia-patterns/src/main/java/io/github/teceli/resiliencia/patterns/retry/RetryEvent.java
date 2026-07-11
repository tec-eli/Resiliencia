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

    record AttemptFailed(Instant timestamp, String name, int attemptNumber, Throwable error) implements RetryEvent {}

    record Success(Instant timestamp, String name, int totalAttempts) implements RetryEvent {}

    record Exhausted(Instant timestamp, String name, int totalAttempts, Throwable lastError) implements RetryEvent {}

    record Rejected(Instant timestamp, String name, int attemptNumber, Throwable error) implements RetryEvent {}

    record Interrupted(Instant timestamp, String name, int attemptNumber, Throwable lastError) implements RetryEvent {}
}
