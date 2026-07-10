package io.github.teceli.resiliencia.patterns.circuitbreaker;

import java.time.Duration;
import java.time.Instant;

/**
 * The state of a {@link CircuitBreaker} at a point in time. Closed, Open, and HalfOpen are the
 * only possible states, modeled as a closed hierarchy so callers can pattern-match exhaustively.
 */
public sealed interface CircuitState {

    /**
     * Calls pass through normally while their outcomes are recorded in the sliding window.
     */
    record Closed() implements CircuitState {
    }

    /**
     * Calls are rejected immediately. {@code openedAt} is when the circuit opened;
     * {@code remainingWait} is how much longer it stays Open before a HalfOpen test call is
     * attempted. Must not be negative.
     */
    record Open(Instant openedAt, Duration remainingWait) implements CircuitState {
        public Open {
            if (remainingWait.isNegative()) {
                throw new IllegalArgumentException("remainingWait must not be negative");
            }
        }
    }

    /**
     * A limited number of test calls are let through to probe whether the downstream has
     * recovered. {@code permitsIssued} counts test calls allowed so far; {@code successes} counts
     * how many of them succeeded.
     */
    record HalfOpen(int permitsIssued, int successes) implements CircuitState {
    }
}
