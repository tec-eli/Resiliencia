package io.github.teceli.resiliencia.core.api;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Result of executing an operation with a pattern.
 * Never throws — returns one of the sealed subtypes to allow exhaustive pattern matching.
 */
public sealed interface Outcome<T> {
    /**
     * Operation succeeded and returned a value. {@code value} may be {@code null} — a successful
     * operation that legitimately returns no result (e.g. {@code Void}) is still a Success, not
     * a Failure.
     */
    record Success<T>(T value) implements Outcome<T> {}

    /**
     * Operation failed with an exception. {@code cause} is always present — a Failure with no
     * cause is not a meaningful outcome.
     */
    record Failure<T>(Throwable cause) implements Outcome<T> {
        public Failure {
            Objects.requireNonNull(cause, "cause must not be null");
        }
    }

    /**
     * Operation did not complete within the configured timeout. {@code timeout} is always
     * present — it is the configured limit that was exceeded.
     */
    record TimedOut<T>(Duration timeout) implements Outcome<T> {
        public TimedOut {
            Objects.requireNonNull(timeout, "timeout must not be null");
        }
    }

    /**
     * Fold over the outcome: apply one function if Success, another if Failure.
     * A TimedOut outcome folds as a failure, passing a {@link ResilientTimeoutException}
     * carrying the exceeded timeout to {@code onFailure}.
     */
    default <U> U fold(Function<T, U> onSuccess,
                        Function<Throwable, U> onFailure) {
        return switch (this) {
            case Success<T>(T value) -> onSuccess.apply(value);
            case Failure<T>(Throwable cause) -> onFailure.apply(cause);
            case TimedOut<T>(Duration timeout) -> onFailure.apply(new ResilientTimeoutException(timeout));
        };
    }
}
