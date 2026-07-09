package io.github.teceli.resiliencia.patterns.retry;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilienciaException;
import io.github.teceli.resiliencia.core.api.ResilienciaTimeoutException;
import io.github.teceli.resiliencia.core.spi.Clock;
import io.github.teceli.resiliencia.core.spi.ResilienceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Retry pattern: execute an operation, retrying on failure up to maxAttempts.
 * Supports exponential backoff with an optional max-delay cap and jitter, and
 * conditional retry (filter which exceptions to retry).
 *
 * Immutable and reusable: each {@code withX} method returns a new, independently
 * usable {@code Retry} instance rather than mutating this one.
 */
public record Retry<T>(int maxAttempts, long initialDelayMs, double backoffMultiplier,
                        long maxDelayMs, double jitterFactor, long overallDeadlineMs,
                        Predicate<Throwable> shouldRetry, List<ResilienceEvent.Listener> listeners, Clock clock)
        implements Resilient<T> {

    private static final Logger log = LoggerFactory.getLogger(Retry.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_INITIAL_DELAY_MS = 100;
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    private static final long DEFAULT_MAX_DELAY_MS = Long.MAX_VALUE;
    private static final double DEFAULT_JITTER_FACTOR = 0.0;
    private static final long DEFAULT_OVERALL_DEADLINE_MS = Long.MAX_VALUE;

    public Retry {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (initialDelayMs < 0) throw new IllegalArgumentException("initialDelay must be >= 0");
        if (backoffMultiplier < 1.0) throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
        if (maxDelayMs < 0) throw new IllegalArgumentException("maxDelay must be >= 0");
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be between 0.0 and 1.0");
        }
        if (overallDeadlineMs < 0) throw new IllegalArgumentException("overallDeadline must be >= 0");
        Objects.requireNonNull(shouldRetry, "shouldRetry must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        listeners = List.copyOf(listeners);
    }

    /**
     * A {@code Retry} instance configured with sensible defaults, ready to use as-is
     * or refine further via {@code withX} methods.
     *
     * By default, retries only on {@code IOException} and its subclasses, which are assumed
     * to be transient (network errors, timeouts, connection resets). Other exceptions are
     * treated as permanent failures. To customize, use {@link #withShouldRetry(Predicate)}.
     */
    public static <T> Retry<T> create() {
        return new Retry<>(DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_DELAY_MS, DEFAULT_BACKOFF_MULTIPLIER,
                DEFAULT_MAX_DELAY_MS, DEFAULT_JITTER_FACTOR, DEFAULT_OVERALL_DEADLINE_MS,
            IOException.class::isInstance,
                List.of(), Clock.systemClock());
    }

    /**
     * Maximum number of attempts, including the first one — {@code withMaxAttempts(1)} disables
     * retrying entirely. Must be at least 1. Default: 3.
     */
    public Retry<T> withMaxAttempts(int maxAttempts) {
        return new Retry<>(maxAttempts, initialDelayMs, backoffMultiplier, maxDelayMs, jitterFactor,
                overallDeadlineMs, shouldRetry, listeners, clock);
    }

    /**
     * Delay before the first retry attempt. Subsequent delays grow from this base according to
     * {@link #withBackoffMultiplier}. Must be at least 0. Default: 100ms.
     */
    public Retry<T> withInitialDelay(long delayMs) {
        return new Retry<>(maxAttempts, delayMs, backoffMultiplier, maxDelayMs, jitterFactor,
                overallDeadlineMs, shouldRetry, listeners, clock);
    }

    /**
     * Factor each backoff delay is multiplied by after every failed attempt, producing
     * exponential growth from {@link #withInitialDelay}. Must be at least 1.0 (1.0 means a
     * constant delay, no growth). Default: 2.0.
     */
    public Retry<T> withBackoffMultiplier(double multiplier) {
        return new Retry<>(maxAttempts, initialDelayMs, multiplier, maxDelayMs, jitterFactor,
                overallDeadlineMs, shouldRetry, listeners, clock);
    }

    /**
     * Cap every backoff delay (including the initial one, after jitter) at the given value,
     * preventing unbounded exponential growth. Delays above the cap are clamped, not rejected.
     */
    public Retry<T> withMaxDelay(long maxDelayMs) {
        return new Retry<>(maxAttempts, initialDelayMs, backoffMultiplier, maxDelayMs, jitterFactor,
                overallDeadlineMs, shouldRetry, listeners, clock);
    }

    /**
     * Randomize each backoff delay uniformly within {@code [delay * (1 - factor), delay * (1 + factor)]}
     * to spread out retries from many clients that failed at the same moment (thundering herd).
     * A factor of 0.0 (the default) disables jitter; 1.0 allows anywhere from zero to double the delay.
     */
    public Retry<T> withJitter(double jitterFactor) {
        return new Retry<>(maxAttempts, initialDelayMs, backoffMultiplier, maxDelayMs, jitterFactor,
                overallDeadlineMs, shouldRetry, listeners, clock);
    }

    /**
     * Bound the total wall-clock time this retry loop is willing to spend across all attempts and
     * backoff waits, measured from the first attempt. Checked only between attempts — never
     * preempts an attempt already in progress, which stays Timeout's responsibility. Once the
     * deadline has passed, the loop stops as if the attempt budget were exhausted (emits
     * {@link RetryEvent.Exhausted} and throws {@link RetryExhaustedException}), even if
     * {@code maxAttempts} has not been reached yet. Disabled (uncapped) by default.
     */
    public Retry<T> withOverallDeadline(long overallDeadlineMs) {
        return new Retry<>(maxAttempts, initialDelayMs, backoffMultiplier, maxDelayMs, jitterFactor,
                overallDeadlineMs, shouldRetry, listeners, clock);
    }

    /**
     * Decide, for each thrown exception, whether it is worth retrying. Evaluated once per failed
     * attempt, before the attempt count and deadline are checked. If the predicate itself throws,
     * that is logged as a warning and treated as {@code false} — a broken predicate rejects the
     * retry instead of replacing the real exception. Default: retries only {@code IOException}
     * and its subclasses.
     */
    public Retry<T> withShouldRetry(Predicate<Throwable> predicate) {
        return new Retry<>(maxAttempts, initialDelayMs, backoffMultiplier, maxDelayMs, jitterFactor,
                overallDeadlineMs, predicate, listeners, clock);
    }

    /**
     * Add a listener notified of every {@link RetryEvent} emitted by this instance. Listener
     * exceptions are logged and otherwise ignored — a broken listener never affects the outcome.
     */
    public Retry<T> withListener(ResilienceEvent.Listener listener) {
        var newListeners = new ArrayList<>(listeners);
        newListeners.add(listener);
        return new Retry<>(maxAttempts, initialDelayMs, backoffMultiplier, maxDelayMs, jitterFactor,
                overallDeadlineMs, shouldRetry, newListeners, clock);
    }

    /**
     * Use a custom {@link Clock} instead of the system clock, e.g. a manual/virtual clock in tests
     * to make backoff assertions deterministic and instant.
     */
    public Retry<T> withClock(Clock clock) {
        return new Retry<>(maxAttempts, initialDelayMs, backoffMultiplier, maxDelayMs, jitterFactor,
                overallDeadlineMs, shouldRetry, listeners, clock);
    }

    @Override
    public String patternName() {
        return "retry";
    }

    @Override
    public PatternKind patternKind() {
        return PatternKind.RETRY;
    }

    /**
     * True once {@link #withOverallDeadline(long)} has been configured with a finite bound,
     * telling Policy this Retry already caps its own total duration.
     */
    @Override
    public boolean hasOwnDeadline() {
        return overallDeadlineMs != DEFAULT_OVERALL_DEADLINE_MS;
    }

    @Override
    public T call(Operation<T> operation) throws ResilienciaException {
        var result = execute(operation);
        return switch (result.outcome()) {
            case Outcome.Success<T>(T value) -> value;
            // execute() never produces TimedOut (an inner Timeout pattern surfaces as a Failure
            // cause instead); the case exists only for exhaustiveness over the sealed Outcome.
            case Outcome.TimedOut<T>(Duration timeout) -> throw new ResilienciaTimeoutException(timeout);
            case Outcome.Failure<T>(Throwable cause) -> {
                if (result.rejected()) {
                    throw new RetryRejectedException(result.attempts(), cause);
                }
                throw new RetryExhaustedException(result.attempts(), cause);
            }
        };
    }

    @Override
    public Outcome<T> outcome(Operation<T> operation) {
        return execute(operation).outcome();
    }

    /**
     * Runs the retry loop once, returning both the outcome and the number of attempts made.
     * The attempt count is needed by {@link #call} to build a {@link RetryExhaustedException} or
     * {@link RetryRejectedException}; {@code rejected} tells {@link #call} which of the two applies.
     */
    private ExecutionResult<T> execute(Operation<T> operation) {
        long delayMs = initialDelayMs;
        var startInstant = clock.instant();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                var result = operation.execute();
                emit(new RetryEvent.Success(clock.instant(), attempt));
                return new ExecutionResult<>(new Outcome.Success<>(result), attempt, false);
            } catch (Exception e) {
                emit(new RetryEvent.AttemptFailed(clock.instant(), attempt, e));

                if (attempt < maxAttempts && testShouldRetry(e) && !deadlineExceeded(startInstant)) {
                    try {
                        sleep(Math.min(applyJitter(delayMs), maxDelayMs));
                    } catch (ResilienciaException interrupted) {
                        return new ExecutionResult<>(new Outcome.Failure<>(interrupted), attempt, false);
                    }
                    delayMs = Math.min((long) (delayMs * backoffMultiplier), maxDelayMs);
                } else if (attempt == maxAttempts || deadlineExceeded(startInstant)) {
                    emit(new RetryEvent.Exhausted(clock.instant(), attempt, e));
                    return new ExecutionResult<>(new Outcome.Failure<>(e), attempt, false);
                } else {
                    emit(new RetryEvent.Rejected(clock.instant(), attempt, e));
                    return new ExecutionResult<>(new Outcome.Failure<>(e), attempt, true);
                }
            }
        }

        throw new AssertionError("Loop must always return");
    }

    /**
     * Whether {@link #overallDeadlineMs} (if configured) has already elapsed since the first
     * attempt. Always false when the deadline is disabled (the default).
     */
    private boolean deadlineExceeded(Instant startInstant) {
        if (overallDeadlineMs == DEFAULT_OVERALL_DEADLINE_MS) {
            return false;
        }
        return !clock.instant().isBefore(startInstant.plusMillis(overallDeadlineMs));
    }

    private record ExecutionResult<T>(Outcome<T> outcome, int attempts, boolean rejected) {}

    /**
     * Shifts the delay by a uniformly random offset in {@code [-delay * jitterFactor, +delay * jitterFactor]}.
     */
    private long applyJitter(long delayMs) {
        var bound = (long) (delayMs * jitterFactor);
        if (bound <= 0) {
            return delayMs;
        }
        // Avoid overflow if delaysMs is extreme and jitterFactor = 1.0 (bound + 1 o delayMs + would be greater than Long.MAX_VALUE).
        bound = Math.min(bound, Long.MAX_VALUE - delayMs);
        return delayMs + ThreadLocalRandom.current().nextLong(-bound, bound + 1);
    }

    private void sleep(long ms) {
        try {
            clock.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResilienciaException("Retry interrupted", e);
        }
    }

    /**
     * A throwing {@code shouldRetry} is logged, not thrown: a bad user predicate must not escape
     * {@code outcome()}, which is documented to never throw.
     */
    private boolean testShouldRetry(Throwable e) {
        try {
            return shouldRetry.test(e);
        } catch (Exception ex) {
            log.warn("shouldRetry threw while testing {}", e.getClass().getSimpleName(), ex);
            return false;
        }
    }

    /** Listener exceptions are logged, not thrown: a bad listener must not affect the outcome. */
    private void emit(RetryEvent event) {
        for (var listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception ex) {
                log.warn("Listener threw while handling {}", event, ex);
            }
        }
    }
}
