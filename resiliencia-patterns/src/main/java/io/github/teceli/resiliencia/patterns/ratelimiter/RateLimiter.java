package io.github.teceli.resiliencia.patterns.ratelimiter;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilientException;
import io.github.teceli.resiliencia.core.api.ResilientTimeoutException;
import io.github.teceli.resiliencia.core.spi.Clock;
import io.github.teceli.resiliencia.core.spi.ResilienceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RateLimiter pattern: bound how many calls may start per time window (fixed window,
 * {@code limit} calls per {@code period}). Windows are aligned to the instant the limiter was
 * created and advance in whole periods, as measured by the {@link Clock}.
 *
 * Callers over the limit either fail fast with {@link RateLimiterException} (default,
 * {@code maxWait} zero) or wait via {@link Clock#sleep} for up to {@code maxWait} until the
 * next window opens; blocking a virtual thread is cheap.
 *
 * Holds live state (the current window and its used permits). Immutable in configuration and
 * thread-safe by design — share one instance across all callers that must compete for the same
 * budget. Each {@code withX} method returns a new, independent RateLimiter with a fresh window.
 */
public final class RateLimiter<T> implements Resilient<T> {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final Duration MAX_MILLIS_DURATION = Duration.ofMillis(Long.MAX_VALUE);

    private final String name;
    private final int limit;
    private final Duration period;
    private final Duration maxWait;
    private final List<ResilienceEvent.Listener> listeners;
    private final Clock clock;

    private final Object lock = new Object();
    private Instant windowStart; // guarded by lock
    private int used;            // guarded by lock

    private RateLimiter(String name, int limit, Duration period, Duration maxWait,
                        List<ResilienceEvent.Listener> listeners, Clock clock) {
        Objects.requireNonNull(name, "name must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        Objects.requireNonNull(period, "period must not be null");
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be positive");
        }
        Objects.requireNonNull(maxWait, "maxWait must not be null");
        if (maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait must be >= 0");
        }
        Objects.requireNonNull(clock, "clock must not be null");
        this.name = name;
        this.limit = limit;
        this.period = period;
        this.maxWait = maxWait;
        this.listeners = List.copyOf(listeners);
        this.clock = clock;
        this.windowStart = clock.instant();
    }

    /**
     * A {@code RateLimiter} allowing {@code limit} calls per {@code period}, rejecting excess
     * calls immediately ({@code maxWait} zero). Refine via {@code withX} methods, e.g.
     * {@link #withMaxWait} to let excess calls wait for the next window instead.
     */
    public static <T> RateLimiter<T> of(String name, int limit, Duration period) {
        return new RateLimiter<>(name, limit, period, Duration.ZERO, List.of(),
                Clock.systemClock());
    }

    /**
     * Maximum number of calls allowed per {@code period}. Must be at least 1.
     */
    public RateLimiter<T> withLimit(int limit) {
        return new RateLimiter<>(name, limit, period, maxWait, listeners, clock);
    }

    /**
     * Length of the fixed window over which {@code limit} calls are allowed. Must be positive.
     */
    public RateLimiter<T> withPeriod(Duration period) {
        return new RateLimiter<>(name, limit, period, maxWait, listeners, clock);
    }

    /**
     * How long an excess call may wait for the next window before being rejected.
     * Zero (the default) rejects immediately.
     */
    public RateLimiter<T> withMaxWait(Duration maxWait) {
        return new RateLimiter<>(name, limit, period, maxWait, listeners, clock);
    }

    /**
     * Add a listener notified of every {@link RateLimiterEvent} emitted by this instance.
     * Listener exceptions are logged and otherwise ignored — a broken listener never affects the
     * outcome.
     */
    public RateLimiter<T> withListener(ResilienceEvent.Listener listener) {
        var newListeners = new ArrayList<>(listeners);
        newListeners.add(listener);
        return new RateLimiter<>(name, limit, period, maxWait, newListeners, clock);
    }

    /**
     * Use a custom {@link Clock} instead of the system clock, e.g. a manual/virtual clock in
     * tests to make window and wait assertions deterministic and instant.
     */
    public RateLimiter<T> withClock(Clock clock) {
        return new RateLimiter<>(name, limit, period, maxWait, listeners, clock);
    }

    /**
     * The name identifying this rate limiter instance, used in events and rejection exceptions.
     */
    public String name() {
        return name;
    }

    /**
     * The configured maximum number of calls per {@code period}.
     */
    public int limit() {
        return limit;
    }

    /**
     * The configured length of the fixed window.
     */
    public Duration period() {
        return period;
    }

    /**
     * How long an excess call may wait for the next window before being rejected.
     */
    public Duration maxWait() {
        return maxWait;
    }

    @Override
    public String patternName() {
        return "rate-limiter";
    }

    @Override
    public PatternKind patternKind() {
        return PatternKind.RATE_LIMITER;
    }

    @Override
    public T call(Operation<T> operation) throws ResilientException {
        return switch (outcome(operation)) {
            case Outcome.Success<T>(T value) -> value;
            // outcome() never produces TimedOut; the case exists only for exhaustiveness
            // over the sealed Outcome.
            case Outcome.TimedOut<T>(var timeout) -> throw new ResilientTimeoutException(timeout);
            case Outcome.Failure<T>(RuntimeException cause) -> throw cause;
            case Outcome.Failure<T>(Throwable cause) ->
                    throw new ResilientException("Operation failed inside rate limiter", cause);
        };
    }

    @Override
    public Outcome<T> outcome(Operation<T> operation) {
        AcquireOutcome acquireOutcome;
        try {
            acquireOutcome = tryAcquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome.Failure<>(
                    new ResilientException("Interrupted while waiting for a rate limiter permit", e));
        }

        if (!acquireOutcome.acquired()) {
            emit(new RateLimiterEvent.Rejected(clock.instant(), name, acquireOutcome.estimatedWait()));
            return new Outcome.Failure<>(new RateLimiterException(name, limit, period, maxWait));
        }

        emit(new RateLimiterEvent.Permitted(clock.instant(), name, acquireOutcome.remainingPermits()));
        try {
            return new Outcome.Success<>(operation.execute());
        } catch (Exception e) {
            return new Outcome.Failure<>(e);
        }
    }

    /**
     * Take a permit from the current window, waiting for later windows while the deadline
     * allows. Waiters race for the next window's permits; a loser keeps waiting until its
     * deadline can no longer be met.
     */
    private AcquireOutcome tryAcquire() throws InterruptedException {
        // Clamp maxWait to prevent overflow in Instant.plus(); see Bulkhead for same pattern.
        var maxWaitClamped = maxWait.compareTo(MAX_MILLIS_DURATION) > 0 ? MAX_MILLIS_DURATION : maxWait;
        var deadline = clock.instant().plus(maxWaitClamped);
        while (true) {
            Duration untilWindowEnd;
            synchronized (lock) {
                var now = clock.instant();
                advanceWindow(now);
                if (used < limit) {
                    used++;
                    return AcquireOutcome.acquired(limit - used);
                }
                untilWindowEnd = Duration.between(now, windowStart.plus(period));
            }
            if (clock.instant().plus(untilWindowEnd).isAfter(deadline)) {
                return AcquireOutcome.rejected(untilWindowEnd);
            }
            // Duration.toMillis() throws ArithmeticException on overflow for extreme values
            // clamp to Long.MAX_VALUE instead of letting that escape.
            var untilWindowEndMillis =
                    untilWindowEnd.compareTo(MAX_MILLIS_DURATION) > 0 ? Long.MAX_VALUE : untilWindowEnd.toMillis();
            clock.sleep(Math.max(1, untilWindowEndMillis));
        }
    }

    /**
     * Result of a permit attempt: either acquired (carrying the permits left in this window), or
     * rejected (carrying the estimated wait until the next permit is likely available).
     */
    private record AcquireOutcome(boolean acquired, int remainingPermits, Duration estimatedWait) {
        static AcquireOutcome acquired(int remainingPermits) {
            return new AcquireOutcome(true, remainingPermits, Duration.ZERO);
        }

        static AcquireOutcome rejected(Duration estimatedWait) {
            return new AcquireOutcome(false, 0, estimatedWait);
        }
    }

    /**
     * Advance the window in whole periods so windows stay aligned to the creation instant,
     * resetting the used count when a new window is entered. Must be called under the lock.
     */
    private void advanceWindow(Instant now) {
        var elapsed = Duration.between(windowStart, now);
        if (elapsed.compareTo(period) >= 0) {
            var periodsElapsed = elapsed.dividedBy(period);
            windowStart = windowStart.plus(period.multipliedBy(periodsElapsed));
            used = 0;
        }
    }

    /** Listener exceptions are logged, not thrown: a bad listener must not affect the outcome. */
    private void emit(RateLimiterEvent event) {
        for (var listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception ex) {
                log.warn("Listener threw while handling {}", event, ex);
            }
        }
    }
}
