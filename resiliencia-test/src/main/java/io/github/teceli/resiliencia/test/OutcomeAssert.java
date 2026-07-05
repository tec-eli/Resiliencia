package io.github.teceli.resiliencia.test;

import io.github.teceli.resiliencia.core.api.Outcome;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;

import java.time.Duration;
import java.util.Objects;

/**
 * AssertJ assertions for {@link Outcome}. Obtain via
 * {@link ResilienciaAssertions#assertThat(Outcome)}.
 */
public final class OutcomeAssert<T> extends AbstractAssert<OutcomeAssert<T>, Outcome<T>> {

    OutcomeAssert(Outcome<T> actual) {
        super(actual, OutcomeAssert.class);
    }

    public OutcomeAssert<T> isSuccess() {
        isNotNull();
        if (!(actual instanceof Outcome.Success<T>)) {
            failWithMessage("Expected outcome to be Success but was <%s>", actual);
        }
        return this;
    }

    public OutcomeAssert<T> hasValue(T expected) {
        isSuccess();
        var value = ((Outcome.Success<T>) actual).value();
        if (!Objects.equals(value, expected)) {
            failWithMessage("Expected Success value <%s> but was <%s>", expected, value);
        }
        return this;
    }

    public OutcomeAssert<T> isFailure() {
        isNotNull();
        if (!(actual instanceof Outcome.Failure<T>)) {
            failWithMessage("Expected outcome to be Failure but was <%s>", actual);
        }
        return this;
    }

    public OutcomeAssert<T> hasFailureOfType(Class<? extends Throwable> type) {
        isFailure();
        var cause = ((Outcome.Failure<T>) actual).cause();
        if (!type.isInstance(cause)) {
            failWithMessage("Expected failure cause of type <%s> but was <%s>", type.getName(), cause);
        }
        return this;
    }

    /**
     * Switch to standard AssertJ throwable assertions on the failure cause for further chaining.
     */
    public AbstractThrowableAssert<?, ? extends Throwable> failureCause() {
        isFailure();
        return Assertions.assertThat(((Outcome.Failure<T>) actual).cause());
    }

    public OutcomeAssert<T> isTimedOut() {
        isNotNull();
        if (!(actual instanceof Outcome.TimedOut<T>)) {
            failWithMessage("Expected outcome to be TimedOut but was <%s>", actual);
        }
        return this;
    }

    public OutcomeAssert<T> isTimedOutAfter(Duration timeout) {
        isTimedOut();
        var actualTimeout = ((Outcome.TimedOut<T>) actual).timeout();
        if (!actualTimeout.equals(timeout)) {
            failWithMessage("Expected TimedOut after <%s> but was after <%s>", timeout, actualTimeout);
        }
        return this;
    }
}
