package io.github.teceli.resiliencia.patterns.bulkhead;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilienciaException;
import io.github.teceli.resiliencia.core.api.ResilienciaTimeoutException;
import io.github.teceli.resiliencia.core.spi.Clock;
import io.github.teceli.resiliencia.core.spi.ResilienceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bulkhead pattern: bound how many calls may execute concurrently, isolating the protected
 * resource from overload. Built on a {@link Semaphore} — callers over the limit either fail
 * fast with {@link BulkheadFullException} (default, {@code maxWait} zero) or block for up to
 * {@code maxWait} until a permit frees up; blocking a virtual thread is cheap.
 *
 * Not a record, unlike Retry and Timeout: a Bulkhead holds live state (the permits). It is
 * still immutable in configuration and thread-safe by design — share one instance across all
 * callers that must compete for the same permits. Each {@code withX} method returns a new,
 * independent Bulkhead with a fresh, unused set of permits.
 */
public final class Bulkhead<T> implements Resilient<T> {

    private static final Logger log = LoggerFactory.getLogger(Bulkhead.class);
    private static final Duration MAX_MILLIS_DURATION = Duration.ofMillis(Long.MAX_VALUE);

    private final int maxConcurrentCalls;
    private final Duration maxWait;
    private final List<ResilienceEvent.Listener> listeners;
    private final Clock clock;
    private final Semaphore permits;

    private Bulkhead(int maxConcurrentCalls, Duration maxWait,
                     List<ResilienceEvent.Listener> listeners, Clock clock) {
        if (maxConcurrentCalls < 1) {
            throw new IllegalArgumentException("maxConcurrentCalls must be >= 1");
        }
        Objects.requireNonNull(maxWait, "maxWait must not be null");
        if (maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait must be >= 0");
        }
        Objects.requireNonNull(clock, "clock must not be null");
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.maxWait = maxWait;
        this.listeners = List.copyOf(listeners);
        this.clock = clock;
        this.permits = new Semaphore(maxConcurrentCalls);
    }

    /**
     * A {@code Bulkhead} allowing the given number of concurrent calls, rejecting excess
     * calls immediately ({@code maxWait} zero). Refine via {@code withX} methods, e.g.
     * {@link #withMaxWait} to let excess calls wait for a permit instead.
     */
    public static <T> Bulkhead<T> of(int maxConcurrentCalls) {
        return new Bulkhead<>(maxConcurrentCalls, Duration.ZERO, List.of(), Clock.systemClock());
    }

    public Bulkhead<T> withMaxConcurrentCalls(int maxConcurrentCalls) {
        return new Bulkhead<>(maxConcurrentCalls, maxWait, listeners, clock);
    }

    /**
     * How long an excess call may wait for a permit before being rejected.
     * Zero (the default) rejects immediately.
     */
    public Bulkhead<T> withMaxWait(Duration maxWait) {
        return new Bulkhead<>(maxConcurrentCalls, maxWait, listeners, clock);
    }

    public Bulkhead<T> withListener(ResilienceEvent.Listener listener) {
        var newListeners = new ArrayList<>(listeners);
        newListeners.add(listener);
        return new Bulkhead<>(maxConcurrentCalls, maxWait, newListeners, clock);
    }

    /**
     * Use a custom {@link Clock} instead of the system clock for event timestamps.
     * The permit wait is enforced against real elapsed time.
     */
    public Bulkhead<T> withClock(Clock clock) {
        return new Bulkhead<>(maxConcurrentCalls, maxWait, listeners, clock);
    }

    public int maxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    public Duration maxWait() {
        return maxWait;
    }

    @Override
    public String patternName() {
        return "bulkhead";
    }

    @Override
    public PatternKind patternKind() {
        return PatternKind.BULKHEAD;
    }

    @Override
    public T call(Operation<T> operation) throws ResilienciaException {
        return switch (outcome(operation)) {
            case Outcome.Success<T>(T value) -> value;
            // outcome() never produces TimedOut; the case exists only for exhaustiveness
            // over the sealed Outcome.
            case Outcome.TimedOut<T>(var timeout) -> throw new ResilienciaTimeoutException(timeout);
            case Outcome.Failure<T>(RuntimeException cause) -> throw cause;
            case Outcome.Failure<T>(Throwable cause) ->
                    throw new ResilienciaException("Operation failed inside bulkhead", cause);
        };
    }

    @Override
    public Outcome<T> outcome(Operation<T> operation) {
        boolean acquired;
        try {
            // Duration.toMillis() throws ArithmeticException on overflow for extreme values;
            // clamp to Long.MAX_VALUE instead of letting that escape.
            var maxWaitMillis = maxWait.compareTo(MAX_MILLIS_DURATION) > 0 ? Long.MAX_VALUE : maxWait.toMillis();
            acquired = permits.tryAcquire(maxWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome.Failure<>(
                    new ResilienciaException("Interrupted while waiting for a bulkhead permit", e));
        }

        if (!acquired) {
            emit(new BulkheadEvent.Rejected(clock.instant(), maxConcurrentCalls, maxWait));
            return new Outcome.Failure<>(new BulkheadFullException(maxConcurrentCalls, maxWait));
        }

        emit(new BulkheadEvent.Permitted(clock.instant(), activeCalls()));
        try {
            return new Outcome.Success<>(operation.execute());
        } catch (Exception e) {
            return new Outcome.Failure<>(e);
        } finally {
            permits.release();
            emit(new BulkheadEvent.Finished(clock.instant(), activeCalls()));
        }
    }

    /**
     * Approximate count of calls currently holding a permit, derived from the semaphore's
     * available permits. Best-effort under concurrency: another thread may acquire or release
     * between this read and the event being observed.
     */
    private int activeCalls() {
        return maxConcurrentCalls - permits.availablePermits();
    }

    /** Listener exceptions are logged, not thrown: a bad listener must not affect the outcome. */
    private void emit(BulkheadEvent event) {
        for (var listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception ex) {
                log.warn("Listener threw while handling {}", event, ex);
            }
        }
    }
}
