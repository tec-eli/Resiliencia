package io.github.teceli.resiliencia.patterns.bulkhead;

import io.github.teceli.resiliencia.core.spi.ResilienceEvent;

import java.time.Duration;
import java.time.Instant;

/**
 * Events emitted by the Bulkhead pattern.
 */
public sealed interface BulkheadEvent extends ResilienceEvent {
    @Override
    default String patternName() {
        return "bulkhead";
    }

    record Permitted(Instant timestamp, int activeCalls) implements BulkheadEvent {}

    record Rejected(Instant timestamp, int maxConcurrentCalls, Duration maxWait) implements BulkheadEvent {}

    record Finished(Instant timestamp, int activeCalls) implements BulkheadEvent {}
}
