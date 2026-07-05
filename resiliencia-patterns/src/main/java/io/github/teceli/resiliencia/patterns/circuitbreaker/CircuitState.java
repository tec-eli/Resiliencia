package io.github.teceli.resiliencia.patterns.circuitbreaker;

import java.time.Duration;
import java.time.Instant;

public sealed interface CircuitState permits CircuitState.Closed, CircuitState.Open, CircuitState.HalfOpen {

    record Closed() implements CircuitState {
    }

    record Open(Instant openedAt, Duration remainingWait) implements CircuitState {
        public Open {
            if (remainingWait.isNegative()) {
                throw new IllegalArgumentException("remainingWait must not be negative");
            }
        }
    }

    record HalfOpen(int permitsIssued, int successes) implements CircuitState {
    }
}
