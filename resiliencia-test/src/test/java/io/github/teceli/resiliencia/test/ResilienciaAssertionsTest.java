package io.github.teceli.resiliencia.test;

import io.github.teceli.resiliencia.core.api.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.github.teceli.resiliencia.test.ResilienciaAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ResilienciaAssertionsTest {

    @Test
    void should_pass_when_successAssertionsMatchOutcome() {
        Outcome<String> outcome = new Outcome.Success<>("done");

        assertThat(outcome).isSuccess().hasValue("done");
    }

    @Test
    void should_pass_when_failureAssertionsMatchOutcome() {
        Outcome<String> outcome = new Outcome.Failure<>(new IllegalStateException("boom"));

        assertThat(outcome)
                .isFailure()
                .hasFailureOfType(IllegalStateException.class)
                .failureCause().hasMessage("boom");
    }

    @Test
    void should_pass_when_timedOutAssertionsMatchOutcome() {
        Outcome<String> outcome = new Outcome.TimedOut<>(Duration.ofMillis(50));

        assertThat(outcome).isTimedOut().isTimedOutAfter(Duration.ofMillis(50));
    }

    @Test
    void should_fail_when_successAssertedOnFailure() {
        Outcome<String> outcome = new Outcome.Failure<>(new IllegalStateException("boom"));

        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> assertThat(outcome).isSuccess())
                .withMessageContaining("Success");
    }

    @Test
    void should_fail_when_valueDiffersFromExpected() {
        Outcome<String> outcome = new Outcome.Success<>("actual");

        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> assertThat(outcome).hasValue("expected"))
                .withMessageContaining("expected");
    }

    @Test
    void should_fail_when_failureCauseTypeDiffers() {
        Outcome<String> outcome = new Outcome.Failure<>(new IllegalStateException("boom"));

        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> assertThat(outcome).hasFailureOfType(IllegalArgumentException.class));
    }

    @Test
    void should_fail_when_timeoutDurationDiffers() {
        Outcome<String> outcome = new Outcome.TimedOut<>(Duration.ofMillis(50));

        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> assertThat(outcome).isTimedOutAfter(Duration.ofMillis(100)));
    }

    @Test
    void should_fail_when_timedOutAssertedOnSuccess() {
        Outcome<String> outcome = new Outcome.Success<>("done");

        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> assertThat(outcome).isTimedOut());
    }
}
