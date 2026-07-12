package io.github.teceli.resiliencia.metrics.bulkhead;

import io.github.teceli.resiliencia.metrics.Counters;

/**
 * Counter/timer-worthy occurrences emitted by a Bulkhead.
 */
public sealed interface BulkheadCounters extends Counters {

    enum Outcome {
        PERMITTED,
        REJECTED
    }

    record Call(String name, Outcome outcome) implements BulkheadCounters {
    }
}
