package io.github.teceli.resiliencia.core.api;

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
     * Fold over the outcome: apply one function if Success, another if Failure.
     */
    default <U> U fold(java.util.function.Function<T, U> onSuccess,
                       java.util.function.Function<Throwable, U> onFailure) {
        return switch (this) {
            case Success<T> s -> onSuccess.apply(s.value());
            case Failure<T> f -> onFailure.apply(f.cause());
        };
    }
}
