package io.github.teceli.resiliencia.patterns.timeout;

import io.github.teceli.resiliencia.core.spi.ResilienceEvent;

import java.time.Duration;
import java.time.Instant;

/**
 * Events emitted by the Timeout pattern.
 */
public sealed interface TimeoutEvent extends ResilienceEvent {
    @Override
    default String patternName() {
        return "timeout";
    }

    record Succeeded(Instant timestamp, Duration elapsed) implements TimeoutEvent {}

    record Failed(Instant timestamp, Throwable error) implements TimeoutEvent {}

    record TimedOut(Instant timestamp, Duration timeout) implements TimeoutEvent {}
}
