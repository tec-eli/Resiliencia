package io.github.teceli.resiliencia.test;

import io.github.teceli.resiliencia.core.api.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.github.teceli.resiliencia.test.ResilienciaAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Note: Import assertThat is overloaded - ResilienciaAssertions.assertThat is used for Outcome,
// org.assertj.core.api.Assertions.assertThat is used for String message validation

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

        var exception = assertThrows(AssertionError.class,
                () -> assertThat(outcome).isSuccess());

        assertThat(exception.getMessage()).contains("Success");
    }

    @Test
    void should_fail_when_valueDiffersFromExpected() {
        Outcome<String> outcome = new Outcome.Success<>("actual");

        var exception = assertThrows(AssertionError.class,
                () -> assertThat(outcome).hasValue("expected"));

        assertThat(exception.getMessage()).contains("expected");
    }

    @Test
    void should_fail_when_failureCauseTypeDiffers() {
        Outcome<String> outcome = new Outcome.Failure<>(new IllegalStateException("boom"));

        var exception = assertThrows(AssertionError.class,
                () -> assertThat(outcome).hasFailureOfType(IllegalArgumentException.class));

        assertThat(exception.getMessage()).contains("IllegalArgumentException");
    }

    @Test
    void should_fail_when_timeoutDurationDiffers() {
        Outcome<String> outcome = new Outcome.TimedOut<>(Duration.ofMillis(50));

        var exception = assertThrows(AssertionError.class,
                () -> assertThat(outcome).isTimedOutAfter(Duration.ofMillis(100)));

        assertThat(exception.getMessage()).contains("0.1S");
    }

    @Test
    void should_fail_when_timedOutAssertedOnSuccess() {
        Outcome<String> outcome = new Outcome.Success<>("done");
        var outcomeAssert = assertThat(outcome);

        assertThrows(AssertionError.class, outcomeAssert::isTimedOut);
    }
}
