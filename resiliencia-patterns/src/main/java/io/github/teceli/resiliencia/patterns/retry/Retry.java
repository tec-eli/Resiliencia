package io.github.teceli.resiliencia.patterns.retry;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilienciaException;
import io.github.teceli.resiliencia.core.spi.Clock;
import io.github.teceli.resiliencia.core.spi.ResilienceEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Retry pattern: execute an operation, retrying on failure up to maxAttempts.
 * Supports exponential backoff and conditional retry (filter which exceptions to retry).
 *
 * Immutable and reusable: each {@code withX} method returns a new, independently
 * usable {@code Retry} instance rather than mutating this one.
 */
public record Retry<T>(int maxAttempts, long initialDelayMs, double backoffMultiplier,
                        Predicate<Throwable> shouldRetry, List<ResilienceEvent.Listener> listeners, Clock clock)
        implements Resilient<T> {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_INITIAL_DELAY_MS = 100;
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;

    public Retry {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (initialDelayMs < 0) throw new IllegalArgumentException("initialDelay must be >= 0");
        if (backoffMultiplier < 1.0) throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
        Objects.requireNonNull(shouldRetry, "shouldRetry must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        listeners = List.copyOf(listeners);
    }

    /**
     * A {@code Retry} instance configured with sensible defaults, ready to use as-is
     * or refine further via {@code withX} methods.
     */
    public static <T> Retry<T> create() {
        return new Retry<>(DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_DELAY_MS, DEFAULT_BACKOFF_MULTIPLIER,
                e -> true, List.of(), Clock.systemClock());
    }

    public Retry<T> withMaxAttempts(int maxAttempts) {
        return new Retry<>(maxAttempts, initialDelayMs, backoffMultiplier, shouldRetry, listeners, clock);
    }

    public Retry<T> withInitialDelay(long delayMs) {
        return new Retry<>(maxAttempts, delayMs, backoffMultiplier, shouldRetry, listeners, clock);
    }

    public Retry<T> withBackoffMultiplier(double multiplier) {
        return new Retry<>(maxAttempts, initialDelayMs, multiplier, shouldRetry, listeners, clock);
    }

    public Retry<T> withShouldRetry(Predicate<Throwable> predicate) {
        return new Retry<>(maxAttempts, initialDelayMs, backoffMultiplier, predicate, listeners, clock);
    }

    public Retry<T> withListener(ResilienceEvent.Listener listener) {
        var newListeners = new ArrayList<>(listeners);
        newListeners.add(listener);
        return new Retry<>(maxAttempts, initialDelayMs, backoffMultiplier, shouldRetry, newListeners, clock);
    }

    /**
     * Use a custom {@link Clock} instead of the system clock, e.g. a manual/virtual clock in tests
     * to make backoff assertions deterministic and instant.
     */
    public Retry<T> withClock(Clock clock) {
        return new Retry<>(maxAttempts, initialDelayMs, backoffMultiplier, shouldRetry, listeners, clock);
    }

    @Override
    public String patternName() {
        return "retry";
    }

    @Override
    public T call(Operation<T> operation) throws ResilienciaException {
        var result = execute(operation);
        return switch (result.outcome()) {
            case Outcome.Success<T>(T value) -> value;
            case Outcome.Failure<T>(Throwable cause) ->
            throw new RetryExhaustedException(result.attempts(), cause);
        };
    }

    @Override
    public Outcome<T> outcome(Operation<T> operation) {
        return execute(operation).outcome();
    }

    /**
     * Runs the retry loop once, returning both the outcome and the number of attempts made.
     * The attempt count is needed by {@link #call} to build a {@link RetryExhaustedException}.
     */
    private ExecutionResult<T> execute(Operation<T> operation) {
        Throwable lastError = null;
        long delayMs = initialDelayMs;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                var result = operation.execute();
                emit(new RetryEvent.Success(clock.instant(), attempt));
                return new ExecutionResult<>(new Outcome.Success<>(result), attempt);
            } catch (Exception e) {
                lastError = e;
                emit(new RetryEvent.AttemptFailed(clock.instant(), attempt, e));

                if (attempt < maxAttempts && shouldRetry.test(e)) {
                    sleep(delayMs);
                    delayMs = (long) (delayMs * backoffMultiplier);
                } else if (attempt == maxAttempts) {
                    emit(new RetryEvent.Exhausted(clock.instant(), attempt, e));
                    return new ExecutionResult<>(new Outcome.Failure<>(e), attempt);
                } else {
                    return new ExecutionResult<>(new Outcome.Failure<>(e), attempt);
                }
            }
        }

        return new ExecutionResult<>(new Outcome.Failure<>(lastError), maxAttempts);
    }

    private record ExecutionResult<T>(Outcome<T> outcome, int attempts) {}

    private void sleep(long ms) {
        try {
            clock.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResilienciaException("Retry interrupted", e);
        }
    }

    private void emit(RetryEvent event) {
        for (var listener : listeners) {
            listener.onEvent(event);
        }
    }
}
