package io.github.teceli.resiliencia.metrics.circuitbreaker;

import io.github.teceli.resiliencia.metrics.Snapshot;

/**
 * Gauge-worthy live state of a CircuitBreaker.
 */
public sealed interface CircuitBreakerSnapshot extends Snapshot {

    /**
     * Deliberately a small, stable enum, not a reuse of {@code patterns}' own {@code CircuitState}
     * sealed type — {@code CircuitState.Open} carries fields useful to the pattern but irrelevant
     * and unstable as gauge content.
     */
    enum Phase {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    record State(String name, Phase phase) implements CircuitBreakerSnapshot {
    }

    record FailureRate(String name, double rate) implements CircuitBreakerSnapshot {
    }
}
