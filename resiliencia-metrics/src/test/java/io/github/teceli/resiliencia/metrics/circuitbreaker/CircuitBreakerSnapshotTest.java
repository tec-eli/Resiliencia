package io.github.teceli.resiliencia.metrics.circuitbreaker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerSnapshotTest {

    @Test
    void should_exposeNameAndPhase_when_stateConstructed() {
        var state = new CircuitBreakerSnapshot.State("myCB", CircuitBreakerSnapshot.Phase.CLOSED);

        assertThat(state.name()).isEqualTo("myCB");
        assertThat(state.phase()).isEqualTo(CircuitBreakerSnapshot.Phase.CLOSED);
    }

    @Test
    void should_exposeNameAndRate_when_failureRateConstructed() {
        var failureRate = new CircuitBreakerSnapshot.FailureRate("myCB", 0.42);

        assertThat(failureRate.name()).isEqualTo("myCB");
        assertThat(failureRate.rate()).isEqualTo(0.42);
    }

    @Test
    void should_declareAllThreePhases_when_phaseEnumInspected() {
        assertThat(CircuitBreakerSnapshot.Phase.values())
            .containsExactly(CircuitBreakerSnapshot.Phase.CLOSED, CircuitBreakerSnapshot.Phase.OPEN,
                CircuitBreakerSnapshot.Phase.HALF_OPEN);
    }

    @Test
    void should_beEqual_when_sameNameAndPhase() {
        var first = new CircuitBreakerSnapshot.State("myCB", CircuitBreakerSnapshot.Phase.OPEN);
        var second = new CircuitBreakerSnapshot.State("myCB", CircuitBreakerSnapshot.Phase.OPEN);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }
}
