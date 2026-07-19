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

    @Test
    void should_allowZeroRate_when_noFailuresRecorded() {
        var failureRate = new CircuitBreakerSnapshot.FailureRate("myCB", 0.0);

        assertThat(failureRate.rate()).isZero();
    }

    @Test
    // Documents current behavior: the record performs no validation, so a negative rate — outside
    // the real [0.0, 1.0] domain — is still accepted rather than rejected.
    void should_allowNegativeRate_when_valueIsInvalidForARealFailureRate() {
        var failureRate = new CircuitBreakerSnapshot.FailureRate("myCB", -0.1);

        assertThat(failureRate.rate()).isEqualTo(-0.1);
    }

    @Test
    // Documents current behavior: the record performs no validation, so a null name is accepted
    // rather than rejected at construction.
    void should_allowNullName_when_nameNotProvided() {
        var state = new CircuitBreakerSnapshot.State(null, CircuitBreakerSnapshot.Phase.CLOSED);

        assertThat(state.name()).isNull();
    }
}
