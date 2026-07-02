package io.github.teceli.resiliencia.core.api;

import java.time.Duration;

/**
 * Result of executing an operation with a pattern.
 * Never throws — returns one of the sealed subtypes to allow exhaustive pattern matching.
 */
public sealed interface Outcome<T> {
    /**
     * Operation succeeded and returned a value.
     */
    record Success<T>(T value) implements Outcome<T> {}

    /**
     * Operation failed with an exception.
     */
    record Failure<T>(Throwable cause) implements Outcome<T> {}

    /**
     * Operation did not complete within the configured timeout.
     */
    record TimedOut<T>(Duration timeout) implements Outcome<T> {}

    /**
     * Fold over the outcome: apply one function if Success, another if Failure.
     * A TimedOut outcome folds as a failure, passing a {@link ResilienciaTimeoutException}
     * carrying the exceeded timeout to {@code onFailure}.
     */
    default <U> U fold(java.util.function.Function<T, U> onSuccess,
                       java.util.function.Function<Throwable, U> onFailure) {
        return switch (this) {
            case Success<T> s -> onSuccess.apply(s.value());
            case Failure<T> f -> onFailure.apply(f.cause());
            case TimedOut<T> t -> onFailure.apply(new ResilienciaTimeoutException(t.timeout()));
        };
    }
}
