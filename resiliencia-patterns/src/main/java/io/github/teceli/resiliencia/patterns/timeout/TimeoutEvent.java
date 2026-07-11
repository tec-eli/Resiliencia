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

    record Succeeded(Instant timestamp, String name, Duration elapsed) implements TimeoutEvent {}

    record Failed(Instant timestamp, String name, Throwable error) implements TimeoutEvent {}

    record TimedOut(Instant timestamp, String name, Duration timeout) implements TimeoutEvent {}

    /** The abandoned worker (interrupted or left running past the timeout) eventually succeeded. */
    record AbandonedWorkerSucceeded(Instant timestamp, String name) implements TimeoutEvent {}

    /** The abandoned worker (interrupted or left running past the timeout) eventually failed. */
    record AbandonedWorkerFailed(Instant timestamp, String name, Throwable cause) implements TimeoutEvent {}
}
