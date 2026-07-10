package io.github.teceli.resiliencia.core.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class OutcomeTest {
    @Test
    void should_throwNullPointerException_when_failureCauseIsNull() {
        assertThatNullPointerException().isThrownBy(() -> new Outcome.Failure<String>(null));
    }

    @Test
    void should_throwNullPointerException_when_timedOutTimeoutIsNull() {
        assertThatNullPointerException().isThrownBy(() -> new Outcome.TimedOut<String>(null));
    }

    @Test
    void should_allowNullValue_when_successCarriesNoResult() {
        // A Success wraps whatever the operation legitimately returned, including null
        // (e.g. a Void-returning operation) — this is not the library returning null itself.
        Outcome<String> outcome = new Outcome.Success<>(null);

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Success.class, s ->
                        assertThat(s.value()).isNull());
    }
    @Test
    void should_holdValue_when_success() {
        Outcome<String> outcome = new Outcome.Success<>("value");

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Success.class, s ->
                        assertThat(s.value()).isEqualTo("value"));
    }

    @Test
    void should_holdCause_when_failure() {
        var cause = new RuntimeException("boom");
        Outcome<String> outcome = new Outcome.Failure<>(cause);

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Failure.class, f ->
                        assertThat(f.cause()).isSameAs(cause));
    }

    @Test
    void should_applyOnSuccessFunction_when_folding_aSuccess() {
        Outcome<String> outcome = new Outcome.Success<>("value");

        var result = outcome.fold(
                value -> "success: " + value,
                cause -> "failure: " + cause.getMessage());

        assertThat(result).isEqualTo("success: value");
    }

    @Test
    void should_applyOnFailureFunction_when_folding_aFailure() {
        Outcome<String> outcome = new Outcome.Failure<>(new RuntimeException("boom"));

        var result = outcome.fold(
                value -> "success: " + value,
                cause -> "failure: " + cause.getMessage());

        assertThat(result).isEqualTo("failure: boom");
    }

    @Test
    void should_matchSuccess_when_usingSwitchExpression() {
        Outcome<Integer> success = new Outcome.Success<>(42);

        var result = switch (success) {
            case Outcome.Success<?> s -> "value=" + s.value();
            case Outcome.Failure<?> f -> "cause=" + f.cause().getMessage();
            case Outcome.TimedOut<?> t -> "timeout=" + t.timeout();
        };

        assertThat(result).isEqualTo("value=42");
    }

    @Test
    void should_matchFailure_when_usingSwitchExpression() {
        Outcome<Integer> failure = new Outcome.Failure<>(new IllegalStateException("bad state"));

        var result = switch (failure) {
            case Outcome.Success<?> s -> "value=" + s.value();
            case Outcome.Failure<?> f -> "cause=" + f.cause().getMessage();
            case Outcome.TimedOut<?> t -> "timeout=" + t.timeout();
        };

        assertThat(result).isEqualTo("cause=bad state");
    }

    @Test
    void should_holdTimeout_when_timedOut() {
        Outcome<String> outcome = new Outcome.TimedOut<>(Duration.ofMillis(50));

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.TimedOut.class, t ->
                        assertThat(t.timeout()).isEqualTo(Duration.ofMillis(50)));
    }

    @Test
    void should_applyOnFailureFunctionWithTimeoutException_when_folding_aTimedOut() {
        Outcome<String> outcome = new Outcome.TimedOut<>(Duration.ofSeconds(2));

        var result = outcome.fold(
                value -> "success: " + value,
                cause -> "failure: " + cause.getClass().getSimpleName());

        assertThat(result).isEqualTo("failure: ResilientTimeoutException");
    }

    @Test
    void should_matchTimedOut_when_usingSwitchExpression() {
        Outcome<Integer> timedOut = new Outcome.TimedOut<>(Duration.ofMillis(100));

        var result = switch (timedOut) {
            case Outcome.Success<?> s -> "value=" + s.value();
            case Outcome.Failure<?> f -> "cause=" + f.cause().getMessage();
            case Outcome.TimedOut<?> t -> "timeout=" + t.timeout();
        };

        assertThat(result).isEqualTo("timeout=" + Duration.ofMillis(100));
    }

    @Test
    void should_beEqual_when_successesHaveSameValue() {
        Outcome<String> first = new Outcome.Success<>("value");
        Outcome<String> second = new Outcome.Success<>("value");

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void should_beEqual_when_failuresHaveSameCause() {
        var cause = new RuntimeException("boom");
        Outcome<String> first = new Outcome.Failure<>(cause);
        Outcome<String> second = new Outcome.Failure<>(cause);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void should_notBeEqual_when_successAndFailureCompared() {
        Outcome<String> success = new Outcome.Success<>("value");
        Outcome<String> failure = new Outcome.Failure<>(new RuntimeException("boom"));

        assertThat(success).isNotEqualTo(failure);
    }
}
