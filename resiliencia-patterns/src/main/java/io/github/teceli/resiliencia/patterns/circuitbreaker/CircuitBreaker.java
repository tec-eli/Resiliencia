package io.github.teceli.resiliencia.patterns.circuitbreaker;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.ResilientException;
import io.github.teceli.resiliencia.core.api.ResilientTimeoutException;
import io.github.teceli.resiliencia.core.api.Resilient;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Predicate;

/**
 * CircuitBreaker pattern: track recent call outcomes in a sliding window and stop calling a
 * failing or slow downstream once the failure or slow-call rate crosses its threshold,
 * rejecting calls immediately instead of piling more load onto something already struggling.
 *
 * Holds live state (the current {@link CircuitState} and its sliding window). Immutable in
 * configuration and thread-safe by design — share one instance across all callers that must
 * observe the same outcomes. Each {@code withX} method returns a new, independent CircuitBreaker,
 * starting back in the Closed state with an empty window.
 */
public final class CircuitBreaker<T> implements Resilient<T> {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final String name;
    private final double failureRateThreshold;
    private final double slowCallRateThreshold;
    private final Duration slowCallDurationThreshold; // null means no call is ever slow
    private final int slidingWindowSize;
    private final Duration waitDurationInOpenState;
    private final int permittedCallsInHalfOpenState;
    private final List<Class<? extends Throwable>> recordOn;
    private final List<Class<? extends Throwable>> ignoreOn;
    private final Predicate<T> recordOnResult;
    private final List<ResilienceEvent.Listener> listeners;
    private final Clock clock;
    private final SlidingWindow window;

    private final AtomicReference<StateSlot> current;

    private static final double MIN_RATE_THRESHOLD = 0.0;
    private static final double MAX_RATE_THRESHOLD = 1.0;
    private static final double DEFAULT_FAILURE_RATE_THRESHOLD = 0.5;
    private static final double DEFAULT_SLOW_CALL_RATE_THRESHOLD = 1.0;
    private static final int DEFAULT_SLIDING_WINDOW_SIZE = 10;
    private static final Duration DEFAULT_WAIT_DURATION_IN_OPEN_STATE = Duration.ofSeconds(60);
    private static final int DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN_STATE = 3;

    private CircuitBreaker(String name, double failureRateThreshold, double slowCallRateThreshold,
                           Duration slowCallDurationThreshold, int slidingWindowSize,
                           Duration waitDurationInOpenState, int permittedCallsInHalfOpenState,
                           List<Class<? extends Throwable>> recordOn, List<Class<? extends Throwable>> ignoreOn,
                           Predicate<T> recordOnResult, List<ResilienceEvent.Listener> listeners, Clock clock) {
        Objects.requireNonNull(name, "name must not be null");
        if (failureRateThreshold <= MIN_RATE_THRESHOLD || failureRateThreshold > MAX_RATE_THRESHOLD) {
            throw new IllegalArgumentException("failureRateThreshold must be between 0.0 (exclusive) and 1.0");
        }
        if (slowCallRateThreshold <= MIN_RATE_THRESHOLD || slowCallRateThreshold > MAX_RATE_THRESHOLD) {
            throw new IllegalArgumentException("slowCallRateThreshold must be between 0.0 (exclusive) and 1.0");
        }
        if (slidingWindowSize < 1) {
            throw new IllegalArgumentException("slidingWindowSize must be >= 1");
        }
        Objects.requireNonNull(waitDurationInOpenState, "waitDurationInOpenState must not be null");
        if (permittedCallsInHalfOpenState < 1) {
            throw new IllegalArgumentException("permittedCallsInHalfOpenState must be >= 1");
        }
        Objects.requireNonNull(recordOn, "recordOn must not be null");
        Objects.requireNonNull(ignoreOn, "ignoreOn must not be null");
        Objects.requireNonNull(recordOnResult, "recordOnResult must not be null");
        Objects.requireNonNull(clock, "clock must not be null");

        this.name = name;
        this.failureRateThreshold = failureRateThreshold;
        this.slowCallRateThreshold = slowCallRateThreshold;
        this.slowCallDurationThreshold = slowCallDurationThreshold;
        this.slidingWindowSize = slidingWindowSize;
        this.waitDurationInOpenState = waitDurationInOpenState;
        this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
        this.recordOn = List.copyOf(recordOn);
        this.ignoreOn = List.copyOf(ignoreOn);
        this.recordOnResult = recordOnResult;
        this.listeners = List.copyOf(listeners);
        this.clock = clock;
        this.window = new SlidingWindow(slidingWindowSize);
        this.current = new AtomicReference<>(new StateSlot(new CircuitState.Closed()));
    }

    /**
     * A {@code CircuitBreaker} identified by {@code name}, starting Closed with the default
     * thresholds and window size. Refine via {@code withX} methods, e.g.
     * {@link #withFailureRateThreshold} to change when the circuit opens.
     */
    public static <T> CircuitBreaker<T> of(String name) {
        return new CircuitBreaker<>(name, DEFAULT_FAILURE_RATE_THRESHOLD, DEFAULT_SLOW_CALL_RATE_THRESHOLD,
            null, DEFAULT_SLIDING_WINDOW_SIZE, DEFAULT_WAIT_DURATION_IN_OPEN_STATE,
            DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN_STATE, List.of(), List.of(), result -> false,
            List.of(), Clock.systemClock());
    }

    /**
     * Fraction of recorded calls that must fail, once the sliding window is full, to open the
     * circuit. Must be between 0.0 (exclusive) and 1.0. Default: 0.5.
     */
    public CircuitBreaker<T> withFailureRateThreshold(double failureRateThreshold) {
        return new CircuitBreaker<>(name, failureRateThreshold, slowCallRateThreshold, slowCallDurationThreshold,
            slidingWindowSize, waitDurationInOpenState, permittedCallsInHalfOpenState, recordOn, ignoreOn,
            recordOnResult, listeners, clock);
    }

    /**
     * Fraction of recorded calls that must exceed {@link #withSlowCallDurationThreshold}, once
     * the sliding window is full, to open the circuit. Must be between 0.0 (exclusive) and 1.0.
     * Default: 1.0 (slow calls alone never open the circuit unless every call is slow).
     */
    public CircuitBreaker<T> withSlowCallRateThreshold(double slowCallRateThreshold) {
        return new CircuitBreaker<>(name, failureRateThreshold, slowCallRateThreshold, slowCallDurationThreshold,
            slidingWindowSize, waitDurationInOpenState, permittedCallsInHalfOpenState, recordOn, ignoreOn,
            recordOnResult, listeners, clock);
    }

    /**
     * What counts as a slow call. Default: no limit — no call is ever counted as slow.
     */
    public CircuitBreaker<T> withSlowCallDurationThreshold(Duration slowCallDurationThreshold) {
        return new CircuitBreaker<>(name, failureRateThreshold, slowCallRateThreshold,
            slowCallDurationThreshold, slidingWindowSize, waitDurationInOpenState,
            permittedCallsInHalfOpenState, recordOn, ignoreOn, recordOnResult, listeners, clock);
    }

    /**
     * Number of most recent calls used to compute the failure and slow-call rates. Thresholds
     * are only evaluated once this many calls have been recorded. Default: 10.
     */
    public CircuitBreaker<T> withSlidingWindowSize(int slidingWindowSize) {
        return new CircuitBreaker<>(name, failureRateThreshold, slowCallRateThreshold, slowCallDurationThreshold,
            slidingWindowSize, waitDurationInOpenState, permittedCallsInHalfOpenState, recordOn, ignoreOn,
            recordOnResult, listeners, clock);
    }

    /**
     * How long the circuit stays Open before moving to HalfOpen to try test calls again.
     * Default: 60 seconds.
     */
    public CircuitBreaker<T> withWaitDurationInOpenState(Duration waitDurationInOpenState) {
        return new CircuitBreaker<>(name, failureRateThreshold, slowCallRateThreshold, slowCallDurationThreshold,
            slidingWindowSize, waitDurationInOpenState, permittedCallsInHalfOpenState, recordOn, ignoreOn,
            recordOnResult, listeners, clock);
    }

    /**
     * Number of test calls allowed through while HalfOpen. All must succeed for the circuit to
     * close; any failure reopens it. Default: 3.
     */
    public CircuitBreaker<T> withPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
        return new CircuitBreaker<>(name, failureRateThreshold, slowCallRateThreshold, slowCallDurationThreshold,
            slidingWindowSize, waitDurationInOpenState, permittedCallsInHalfOpenState, recordOn, ignoreOn,
            recordOnResult, listeners, clock);
    }

    /**
     * Exception types that count as failures. Default: any {@code Exception}. A type also
     * listed in {@link #withIgnoreOn} is not recorded — ignoreOn takes precedence.
     */
    public CircuitBreaker<T> withRecordOn(List<Class<? extends Throwable>> recordOn) {
        return new CircuitBreaker<>(name, failureRateThreshold, slowCallRateThreshold, slowCallDurationThreshold,
            slidingWindowSize, waitDurationInOpenState, permittedCallsInHalfOpenState, recordOn, ignoreOn,
            recordOnResult, listeners, clock);
    }

    /**
     * Exception types that are never recorded as failures, even if also matched by
     * {@link #withRecordOn}.
     */
    public CircuitBreaker<T> withIgnoreOn(List<Class<? extends Throwable>> ignoreOn) {
        return new CircuitBreaker<>(name, failureRateThreshold, slowCallRateThreshold, slowCallDurationThreshold,
            slidingWindowSize, waitDurationInOpenState, permittedCallsInHalfOpenState, recordOn, ignoreOn,
            recordOnResult, listeners, clock);
    }

    /**
     * Predicate evaluated against a successful return value to record it as a failure anyway,
     * even though no exception was thrown — e.g. an HTTP client returning a 200 with an error
     * body. If the predicate itself throws, that is logged as a warning and treated as
     * {@code false} — a broken predicate never turns a successful call into a reported failure.
     * Default: no result is ever recorded as a failure.
     */
    public CircuitBreaker<T> withRecordOnResult(Predicate<T> recordOnResult) {
        return new CircuitBreaker<>(name, failureRateThreshold, slowCallRateThreshold, slowCallDurationThreshold,
            slidingWindowSize, waitDurationInOpenState, permittedCallsInHalfOpenState, recordOn, ignoreOn,
            recordOnResult, listeners, clock);
    }

    /**
     * Add a listener notified of every {@link CircuitBreakerEvent} emitted by this instance.
     * Listener exceptions are logged and otherwise ignored — a broken listener never affects the
     * outcome.
     */
    public CircuitBreaker<T> withListener(ResilienceEvent.Listener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        var newListeners = new ArrayList<>(listeners);
        newListeners.add(listener);
        return new CircuitBreaker<>(name, failureRateThreshold, slowCallRateThreshold, slowCallDurationThreshold,
            slidingWindowSize, waitDurationInOpenState, permittedCallsInHalfOpenState, recordOn, ignoreOn,
            recordOnResult, newListeners, clock);
    }

    /**
     * Use a custom {@link Clock} instead of the system clock, e.g. a manual/virtual clock in
     * tests to make wait-duration and half-open transition assertions deterministic and instant.
     */
    public CircuitBreaker<T> withClock(Clock clock) {
        return new CircuitBreaker<>(name, failureRateThreshold, slowCallRateThreshold, slowCallDurationThreshold,
            slidingWindowSize, waitDurationInOpenState, permittedCallsInHalfOpenState, recordOn, ignoreOn,
            recordOnResult, listeners, clock);
    }

    /**
     * The current state, computed fresh on each call: for {@link CircuitState.Open}, the
     * returned {@code remainingWait} reflects the time left until a HalfOpen test call is
     * attempted, not the originally configured {@code waitDurationInOpenState}.
     *
     * <p>For {@link CircuitState.HalfOpen}, {@code permitsIssued} and {@code successes} are read
     * from two independent atomics, not under a single lock, so this is a best-effort,
     * non-atomic snapshot: a concurrent test call can complete between the two reads, meaning the
     * pair of values returned may never have existed together at any single instant.
     */
    public CircuitState state() {
        var slot = current.get();
        return switch (slot.publicState) {
            case CircuitState.Closed closed -> closed;
            case CircuitState.HalfOpen halfOpen ->
                new CircuitState.HalfOpen(slot.permitsIssued.get(), slot.successes.get());
            case CircuitState.Open open -> {
                var remaining = Duration.between(clock.instant(), openDeadline(open.openedAt()));
                yield new CircuitState.Open(open.openedAt(), remaining.isNegative() ? Duration.ZERO : remaining);
            }
        };
    }

    /**
     * The instant a HalfOpen test call becomes due, i.e. {@code openedAt + waitDurationInOpenState}.
     * Clamped to {@link Instant#MAX} instead of letting {@code Instant.plus} throw when
     * {@code openedAt} is already near the representable range's end (e.g. a contrived clock) —
     * an unreachable deadline just means the circuit correctly never transitions early.
     */
    private Instant openDeadline(Instant openedAt) {
        try {
            return openedAt.plus(waitDurationInOpenState);
        } catch (DateTimeException | ArithmeticException e) {
            return Instant.MAX;
        }
    }

    @Override
    public PatternKind patternKind() {
        return PatternKind.CIRCUIT_BREAKER;
    }

    @Override
    public String patternName() {
        return "circuit-breaker";
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
                    throw new ResilientException("Operation failed inside circuit breaker", cause);
        };
    }

    @Override
    public Outcome<T> outcome(Operation<T> operation) {
        var rejection = tryAcquirePermission();
        if (rejection != null) {
            return new Outcome.Failure<>(rejection);
        }
        var start = clock.instant();
        try {
            var result = operation.execute();
            recordOutcome(testRecordOnResult(result), start);
            return new Outcome.Success<>(result);
        } catch (Exception e) {
            recordOutcome(isFailure(e), start);
            return new Outcome.Failure<>(e);
        } catch (Error e) {
            // Not wrapped into Outcome, consistent with Timeout: an Error is not a business
            // outcome. Still recorded as a failed test call so a HalfOpen permit consumed
            // above is resolved instead of leaking the circuit's HalfOpen budget forever.
            recordOutcome(true, start);
            throw e;
        }
    }

    /**
     * Whether a thrown exception counts as a failure for rate purposes: {@code ignoreOn} takes
     * precedence over {@code recordOn}. When {@code recordOn} is empty (the default), any
     * exception counts — {@code outcome()} only calls this for {@code Exception}, never
     * {@code Error}, so there is no need to special-case it here.
     */
    private boolean isFailure(Throwable thrown) {
        if (ignoreOn.stream().anyMatch(type -> type.isInstance(thrown))) {
            return false;
        }
        if (!recordOn.isEmpty()) {
            return recordOn.stream().anyMatch(type -> type.isInstance(thrown));
        }
        return true;
    }

    /**
     * Decide whether a call may proceed given the current state: always in Closed, never in
     * Open until {@code waitDurationInOpenState} has elapsed (which flips the state to HalfOpen
     * and retries), and up to {@code permittedCallsInHalfOpenState} times while HalfOpen.
     * Returns {@code null} when the call is permitted, otherwise the exception to fail with.
     */
    private CircuitBreakerOpenException tryAcquirePermission() {
        var slot = current.get();
        return switch (slot.publicState) {
            case CircuitState.Closed c -> null;
            case CircuitState.Open open -> {
                var deadline = openDeadline(open.openedAt());
                var now = clock.instant();
                if (now.isBefore(deadline)) {
                    emit(new CircuitBreakerEvent.Rejected(
                        clock.instant(), name, CircuitBreakerEvent.RejectingPhase.OPEN));
                    yield CircuitBreakerOpenException.forOpenState(
                        name, open.openedAt(), Duration.between(now, deadline));
                }
                if (current.compareAndSet(slot, new StateSlot(new CircuitState.HalfOpen(0, 0)))) {
                    emit(new CircuitBreakerEvent.HalfOpened(clock.instant(), name));
                }
                yield tryAcquirePermission();
            }
            case CircuitState.HalfOpen halfOpen -> {
                // Re-reads current every iteration and re-validates the permit CAS against it,
                // so a stale slot from before a concurrent transition can never grant a permit.
                while (true) {
                    var currentSlot = current.get();
                    if (!(currentSlot.publicState instanceof CircuitState.HalfOpen)) {
                        yield tryAcquirePermission();
                    }
                    var issued = currentSlot.permitsIssued.get();
                    if (issued >= permittedCallsInHalfOpenState) {
                        emit(new CircuitBreakerEvent.Rejected(
                            clock.instant(), name, CircuitBreakerEvent.RejectingPhase.HALF_OPEN));
                        yield CircuitBreakerOpenException.forHalfOpenState(name);
                    }
                    if (currentSlot.permitsIssued.compareAndSet(issued, issued + 1)
                            && current.get() == currentSlot) {
                        yield null;
                    }
                }
            }
        };
    }

    /**
     * Record the outcome in the sliding window and emit {@code CallRecorded}, then let the
     * current state decide what, if anything, changes: Closed checks the rate thresholds,
     * HalfOpen checks this single test call. A concurrent Open (another thread just opened the
     * circuit) has nothing left to evaluate.
     */
    private void recordOutcome(boolean failed, Instant start) {
        var elapsed = Duration.between(start, clock.instant());
        var slow = slowCallDurationThreshold != null && elapsed.compareTo(slowCallDurationThreshold) > 0;
        window.observe(failed, slow);
        emit(new CircuitBreakerEvent.CallRecorded(clock.instant(), name, !failed, elapsed, window.failureRate()));

        var slot = current.get();
        switch (slot.publicState) {
            case CircuitState.Closed closed -> evaluateFailureThresholds(slot);
            case CircuitState.HalfOpen halfOpen -> evaluateHalfOpenOutcome(slot, failed);
            case CircuitState.Open open -> { /* another thread already opened it; nothing to do */ }
        }
    }

    /**
     * Open the circuit once the sliding window is full and either rate threshold is met or
     * exceeded. Failure rate is checked first: when both are met, the circuit still opens once,
     * reported as the failure-rate reason.
     */
    private void evaluateFailureThresholds(StateSlot slot) {
        if (!window.isFull()) {
            return;
        }
        if (window.failureRate() >= failureRateThreshold) {
            open(slot, CircuitBreakerEvent.Reason.FAILURE_RATE_EXCEEDED);
        } else if (window.slowCallRate() >= slowCallRateThreshold) {
            open(slot, CircuitBreakerEvent.Reason.SLOW_CALL_RATE_EXCEEDED);
        }
    }

    /**
     * HalfOpen ignores the sliding window entirely: any failed test call reopens the circuit
     * immediately, and it closes only once every permitted test call has succeeded.
     */
    private void evaluateHalfOpenOutcome(StateSlot slot, boolean failed) {
        if (failed) {
            open(slot, CircuitBreakerEvent.Reason.FAILURE_RATE_EXCEEDED);
            return;
        }
        if (slot.successes.incrementAndGet() >= permittedCallsInHalfOpenState) {
            close(slot);
        }
    }

    private void open(StateSlot from, CircuitBreakerEvent.Reason reason) {
        // Captured once so the Open state's openedAt and the Opened event's timestamp always
        // agree on the exact instant the circuit opened, instead of two separate clock reads.
        var openedAt = clock.instant();
        if (current.compareAndSet(from, new StateSlot(new CircuitState.Open(openedAt, waitDurationInOpenState)))) {
            emit(new CircuitBreakerEvent.Opened(openedAt, name, reason));
        }
    }

    /**
     * Transitions the circuit to Closed and resets the sliding window.
     */
    private void close(StateSlot from) {
        if (current.compareAndSet(from, new StateSlot(new CircuitState.Closed()))) {
            window.reset();
            emit(new CircuitBreakerEvent.Closed(clock.instant(), name, from.successes.get()));
        }
    }

    /**
     * A throwing {@code recordOnResult} is logged, not thrown: a bad user predicate must not turn
     * a successful call into a reported failure.
     */
    private boolean testRecordOnResult(T result) {
        try {
            return recordOnResult.test(result);
        } catch (Exception ex) {
            log.warn("recordOnResult threw while testing a successful result", ex);
            return false;
        }
    }

    /** Listener exceptions are logged, not thrown: a bad listener must not affect the outcome. */
    private void emit(CircuitBreakerEvent event) {
        for (var listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception ex) {
                log.warn("Listener threw while handling {}", event, ex);
            }
        }
    }

    /**
     * Observes the last {@code capacity} call outcomes to track failure and slow-call rates.
     * Thread-safe for concurrent recording and reading of metrics.
     */
    private static final class SlidingWindow {
        private final boolean[] failures;
        private final boolean[] slowCalls;
        private final int capacity;
        private int writeIndex = 0;
        private int filledCount = 0;
        private final StampedLock lock = new StampedLock();

        private SlidingWindow(int capacity) {
            this.capacity = capacity;
            this.failures = new boolean[capacity];
            this.slowCalls = new boolean[capacity];
        }

        void observe(boolean isFailure, boolean isSlow) {
            long stamp = lock.writeLock();
            try {
                failures[writeIndex] = isFailure;
                slowCalls[writeIndex] = isSlow;
                writeIndex = (writeIndex + 1) % capacity;
                if (filledCount < capacity) {
                    filledCount++;
                }
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        boolean isFull() {
            long stamp = lock.readLock();
            try {
                return filledCount == capacity;
            } finally {
                lock.unlockRead(stamp);
            }
        }

        void reset() {
            long stamp = lock.writeLock();
            try {
                writeIndex = 0;
                filledCount = 0;
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        double failureRate() {
            return computeRate(failures);
        }

        double slowCallRate() {
            return computeRate(slowCalls);
        }

        private double computeRate(boolean[] flags) {
            long stamp = lock.tryOptimisticRead();
            int fc = filledCount;
            int count = 0;
            for (int i = 0; i < fc; i++) {
                if (flags[i]) {
                    count++;
                }
            }

            if (!lock.validate(stamp)) {
                stamp = lock.readLock();
                try {
                    return rateOf(flags);
                } finally {
                    lock.unlockRead(stamp);
                }
            }
            return (fc == 0) ? 0.0 : (double) count / fc;
        }

        private double rateOf(boolean[] flags) {
            if (filledCount == 0) {
                return 0.0;
            }
            int count = 0;
            for (int i = 0; i < filledCount; i++) {
                if (flags[i]) {
                    count++;
                }
            }
            return (double) count / filledCount;
        }
    }

    /**
     * Single atomic source of truth for the state machine: pairs the public, immutable
     * {@link CircuitState} with the mutable HalfOpen counters, so a state transition and its
     * counters always change together in one CAS on {@link #current} — never as two separate
     * writes that another thread could observe half-done. The counters are only meaningful
     * while {@code publicState} is a {@link CircuitState.HalfOpen}; Closed and Open instances
     * simply carry unused, freshly-zeroed ones. Kept private so the immutable snapshot returned
     * by {@link #state()} is the only thing ever exposed outside the breaker.
     */
    private static final class StateSlot {
        private final CircuitState publicState;
        private final AtomicInteger permitsIssued = new AtomicInteger(0);
        private final AtomicInteger successes = new AtomicInteger(0);

        private StateSlot(CircuitState publicState) {
            this.publicState = publicState;
        }
    }
}

