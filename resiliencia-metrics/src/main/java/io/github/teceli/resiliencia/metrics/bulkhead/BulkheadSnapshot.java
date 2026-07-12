package io.github.teceli.resiliencia.metrics.bulkhead;

import io.github.teceli.resiliencia.metrics.Snapshot;

/**
 * Gauge-worthy live state of a Bulkhead.
 */
public sealed interface BulkheadSnapshot extends Snapshot {

    record ActiveCalls(String name, int count) implements BulkheadSnapshot {
    }
}
