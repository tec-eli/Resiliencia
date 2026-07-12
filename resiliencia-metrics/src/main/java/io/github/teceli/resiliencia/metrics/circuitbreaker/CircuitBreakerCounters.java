package io.github.teceli.resiliencia.metrics.circuitbreaker;

import io.github.teceli.resiliencia.metrics.Counters;
import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreakerEvent;

import java.time.Duration;

/**
 * Counter/timer-worthy occurrences emitted by a CircuitBreaker.
 */
public sealed interface CircuitBreakerCounters extends Counters {

    record Transition(String name, CircuitBreakerSnapshot.Phase to, CircuitBreakerEvent.Reason reason)
        implements CircuitBreakerCounters {
    }

    /**
     * Emitted alongside a Transition(to=CLOSED): every Closed transition originates from HalfOpen
     * in the current state machine, and successfulTestCalls only has meaning for that specific
     * case. Kept as its own variant rather than a field on Transition, whose fields are meaningful
     * for every (to, reason) pair it represents — folding a HalfOpen-only value into it would mean
     * constructing Transition from partial information depending on which transition fired.
     */
    record ClosedFromHalfOpen(String name, int successfulTestCalls) implements CircuitBreakerCounters {
    }

    record CallRecorded(String name, boolean successful, Duration elapsed) implements CircuitBreakerCounters {
    }

    /**
     * A call rejected without executing, because the circuit was Open or HalfOpen with no permits
     * left. Reuses {@link CircuitBreakerEvent.RejectingPhase} directly — the enum the source event
     * carries — rather than a metrics-local duplicate, the same convention Transition already
     * follows by reusing {@link CircuitBreakerEvent.Reason} as-is.
     */
    record Rejected(String name, CircuitBreakerEvent.RejectingPhase phase) implements CircuitBreakerCounters {
    }
}
