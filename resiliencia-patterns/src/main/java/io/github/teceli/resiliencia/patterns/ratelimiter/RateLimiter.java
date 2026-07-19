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

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Number of consecutive CAS failures in {@link #tryAcquire()} that back off with
     * {@link Thread#onSpinWait()} before escalating to {@link Thread#yield()}. Bounds CPU burn
     * from tight CAS retries under heavy contention, ahead of the {@code maxWait}-based blocking
     * fallback for a full window.
     */
    private static final int CAS_SPIN_RETRY_THRESHOLD = 8;

    private final String name;
    private final int limit;
    private final Duration period;
    private final Duration maxWait;
    private final List<ResilienceEvent.Listener> listeners;
    private final Clock clock;

    private final AtomicReference<WindowState> state;

    /**
     * Atomic source of truth for the window state: window start time and used permits.
     * All updates use CAS (Compare-And-Set) to ensure atomicity under concurrent access.
     */
    private record WindowState(Instant windowStart, int used) {
    }

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
        this.state = new AtomicReference<>(new WindowState(clock.instant(), 0));
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
        Objects.requireNonNull(listener, "listener must not be null");
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
        var deadline = safePlus(clock.instant(), clampToMaxMillis(maxWait));
        var casRetries = 0;
        while (true) {
            var now = clock.instant();
            var current = state.get();
            var advanced = advanceWindow(current, now);

            if (advanced.used < limit) {
                var acquired = attemptPermit(current, advanced);
                if (acquired.isPresent()) {
                    return acquired.get();
                }
                casRetries = backOff(casRetries);
                checkNotInterruptedWhileSpinning();
                continue;
            }

            var outcome = awaitNextWindow(now, advanced, deadline);
            if (outcome.isPresent()) {
                return outcome.get();
            }
        }
    }

    /** CAS {@code current} to one more used permit in {@code advanced}'s window; empty on CAS loss. */
    private Optional<AcquireOutcome> attemptPermit(WindowState current, WindowState advanced) {
        var newState = new WindowState(advanced.windowStart, advanced.used + 1);
        if (state.compareAndSet(current, newState)) {
            return Optional.of(AcquireOutcome.acquired(limit - newState.used));
        }
        return Optional.empty();
    }

    /**
     * Throws if this thread has been interrupted while spinning in the CAS-retry back-off loop,
     * so a spinning waiter notices interruption instead of burning CPU until the CAS eventually
     * succeeds. Clears the interrupt status as a side effect, same as {@link Thread#sleep}; the
     * caller restores it via {@code Thread.currentThread().interrupt()} once it catches this.
     */
    private static void checkNotInterruptedWhileSpinning() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Interrupted while spinning for a rate limiter permit");
        }
    }

    /**
     * Back off after a CAS loss under concurrent contention: spin briefly, then escalate to
     * yielding. Returns the incremented retry count.
     */
    private static int backOff(int casRetries) {
        var retries = casRetries + 1;
        if (retries <= CAS_SPIN_RETRY_THRESHOLD) {
            Thread.onSpinWait();
        } else {
            Thread.yield();
        }
        return retries;
    }

    /**
     * The current window is full: reject if the next window is unreachable (see {@link #safePlus})
     * or falls past {@code deadline}; otherwise sleep until it opens and return empty so the
     * caller retries.
     */
    private Optional<AcquireOutcome> awaitNextWindow(Instant now, WindowState advanced, Instant deadline)
            throws InterruptedException {
        Instant nextWindowStart;
        try {
            nextWindowStart = advanced.windowStart.plus(period);
        } catch (DateTimeException | ArithmeticException e) {
            return Optional.of(AcquireOutcome.rejected(Duration.between(now, Instant.MAX)));
        }
        var untilWindowEnd = Duration.between(now, nextWindowStart);

        if (safePlus(clock.instant(), untilWindowEnd).isAfter(deadline)) {
            return Optional.of(AcquireOutcome.rejected(untilWindowEnd));
        }
        clock.sleep(Math.max(1, clampToMaxMillis(untilWindowEnd).toMillis()));
        return Optional.empty();
    }

    /**
     * Advance the window in whole periods so windows stay aligned to the creation instant,
     * resetting the used count when a new window is entered.
     *
     * @param current the current window state
     * @param now     the current time
     * @return a WindowState with advanced windowStart and reset used (if period passed), or
     *         the same as current if no period has passed
     */
    private WindowState advanceWindow(WindowState current, Instant now) {
        var elapsed = Duration.between(current.windowStart, now);
        if (elapsed.compareTo(period) >= 0) {
            // Clamp elapsed and fall back to resetting the window straight to `now` if the
            // multiplied-back duration still overflows Instant's representable range.
            try {
                var periodsElapsed = clampToMaxMillis(elapsed).dividedBy(period);
                var newWindowStart = current.windowStart.plus(period.multipliedBy(periodsElapsed));
                return new WindowState(newWindowStart, 0);
            } catch (DateTimeException | ArithmeticException e) {
                return new WindowState(now, 0);
            }
        }
        return current;
    }

    /**
     * {@code instant + duration}, clamped to {@link Instant#MAX} instead of throwing on overflow.
     */
    private static Instant safePlus(Instant instant, Duration duration) {
        try {
            return instant.plus(duration);
        } catch (DateTimeException | ArithmeticException e) {
            return Instant.MAX;
        }
    }

    /** Clamps {@code duration} to {@link #MAX_MILLIS_DURATION} so {@code toMillis()} cannot overflow. */
    private static Duration clampToMaxMillis(Duration duration) {
        return duration.compareTo(MAX_MILLIS_DURATION) > 0 ? MAX_MILLIS_DURATION : duration;
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
