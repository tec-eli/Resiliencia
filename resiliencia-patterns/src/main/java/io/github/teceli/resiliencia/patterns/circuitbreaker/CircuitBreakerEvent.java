package io.github.teceli.resiliencia.patterns.circuitbreaker;

import io.github.teceli.resiliencia.core.spi.ResilienceEvent;

import java.time.Duration;
import java.time.Instant;

/**
 * Events emitted by the CircuitBreaker pattern.
 */
public sealed interface CircuitBreakerEvent extends ResilienceEvent permits CircuitBreakerEvent.CallRecorded,
    CircuitBreakerEvent.Closed,
    CircuitBreakerEvent.HalfOpened,
    CircuitBreakerEvent.Opened {

    @Override
    default String patternName() {
        return "circuit-breaker";
    }

    enum Reason {
        FAILURE_RATE_EXCEEDED,
        SLOW_CALL_RATE_EXCEEDED
    }

    record Opened(Instant timestamp, String name, Reason reason) implements CircuitBreakerEvent {}

    record Closed(Instant timestamp, String name, int numberOfSuccessfulTestCalls) implements CircuitBreakerEvent {
    }

    record HalfOpened(Instant timestamp, String name) implements CircuitBreakerEvent {}

    record CallRecorded(Instant timestamp, String name, boolean isSuccessful, Duration elapsedTime,
                         double currentFailureRate) implements CircuitBreakerEvent {}
}
