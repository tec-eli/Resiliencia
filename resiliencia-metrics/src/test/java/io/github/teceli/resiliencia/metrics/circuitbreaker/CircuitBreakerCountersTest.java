package io.github.teceli.resiliencia.metrics.circuitbreaker;

import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreakerEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerCountersTest {

    @Test
    void should_exposeNameToPhaseAndReason_when_transitionConstructed() {
        var transition = new CircuitBreakerCounters.Transition("myCB", CircuitBreakerSnapshot.Phase.OPEN,
            CircuitBreakerEvent.Reason.FAILURE_RATE_EXCEEDED);

        assertThat(transition.name()).isEqualTo("myCB");
        assertThat(transition.to()).isEqualTo(CircuitBreakerSnapshot.Phase.OPEN);
        assertThat(transition.reason()).isEqualTo(CircuitBreakerEvent.Reason.FAILURE_RATE_EXCEEDED);
    }

    @Test
    void should_allowNullReason_when_transitionNotCausedByFailureOrSlowRate() {
        var transition = new CircuitBreakerCounters.Transition("myCB", CircuitBreakerSnapshot.Phase.HALF_OPEN, null);

        assertThat(transition.reason()).isNull();
    }

    @Test
    void should_exposeNameAndSuccessfulTestCalls_when_closedFromHalfOpenConstructed() {
        var closedFromHalfOpen = new CircuitBreakerCounters.ClosedFromHalfOpen("myCB", 5);

        assertThat(closedFromHalfOpen.name()).isEqualTo("myCB");
        assertThat(closedFromHalfOpen.successfulTestCalls()).isEqualTo(5);
    }

    @Test
    void should_exposeNameSuccessfulAndElapsed_when_callRecordedConstructed() {
        var callRecorded = new CircuitBreakerCounters.CallRecorded("myCB", true, Duration.ofMillis(50));

        assertThat(callRecorded.name()).isEqualTo("myCB");
        assertThat(callRecorded.successful()).isTrue();
        assertThat(callRecorded.elapsed()).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void should_exposeNameAndPhase_when_rejectedConstructed() {
        var rejected = new CircuitBreakerCounters.Rejected("myCB", CircuitBreakerEvent.RejectingPhase.OPEN);

        assertThat(rejected.name()).isEqualTo("myCB");
        assertThat(rejected.phase()).isEqualTo(CircuitBreakerEvent.RejectingPhase.OPEN);
    }

    @Test
    void should_beEqual_when_sameFieldsAcrossVariants() {
        var first = new CircuitBreakerCounters.CallRecorded("myCB", false, Duration.ofMillis(10));
        var second = new CircuitBreakerCounters.CallRecorded("myCB", false, Duration.ofMillis(10));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void should_allowZeroElapsed_when_callRecordedInstantly() {
        var callRecorded = new CircuitBreakerCounters.CallRecorded("myCB", true, Duration.ZERO);

        assertThat(callRecorded.elapsed()).isEqualTo(Duration.ZERO);
    }

    @Test
    void should_allowNegativeElapsed_when_valueIsInvalidForARealCall() {
        var callRecorded = new CircuitBreakerCounters.CallRecorded("myCB", true, Duration.ofMillis(-5));

        assertThat(callRecorded.elapsed())
            .as("record performs no validation, so an elapsed duration that can't occur from real timing is accepted")
            .isEqualTo(Duration.ofMillis(-5));
    }

    @Test
    void should_allowZeroSuccessfulTestCalls_when_closedFromHalfOpenWithNoTestCalls() {
        var closedFromHalfOpen = new CircuitBreakerCounters.ClosedFromHalfOpen("myCB", 0);

        assertThat(closedFromHalfOpen.successfulTestCalls()).isZero();
    }

    @Test
    void should_allowNegativeSuccessfulTestCalls_when_valueIsInvalidForARealTransition() {
        var closedFromHalfOpen = new CircuitBreakerCounters.ClosedFromHalfOpen("myCB", -1);

        assertThat(closedFromHalfOpen.successfulTestCalls())
            .as("record performs no validation, so a count that can't occur from a real HalfOpen-to-Closed "
                + "transition is still accepted")
            .isEqualTo(-1);
    }

    @Test
    void should_allowNullName_when_nameNotProvided() {
        var callRecorded = new CircuitBreakerCounters.CallRecorded(null, true, Duration.ofMillis(10));

        assertThat(callRecorded.name())
            .as("record performs no validation, so a null name is accepted rather than rejected")
            .isNull();
    }
}
