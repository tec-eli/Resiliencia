package io.github.teceli.resiliencia.patterns.timeout;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilienciaException;
import io.github.teceli.resiliencia.core.api.ResilienciaTimeoutException;
import io.github.teceli.resiliencia.core.spi.Clock;
import io.github.teceli.resiliencia.core.spi.ResilienceEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Timeout pattern: execute an operation on a virtual thread and bound how long the caller
 * waits for it. When the deadline passes, the worker thread is interrupted — a real
 * cancellation signal, not polling — and the caller gets a {@link ResilienciaTimeoutException}
 * (or {@link Outcome.TimedOut} via {@link #outcome}). Whether the operation actually stops
 * depends on it responding to interruption; the caller is unblocked either way.
 *
 * The deadline is enforced against real elapsed time; the {@link Clock} is used only for
 * event timestamps, so a manual clock in tests affects observability data, not the deadline.
 *
 * Immutable and reusable: each {@code withX} method returns a new, independently usable
 * {@code Timeout} instance rather than mutating this one.
 */
public record Timeout<T>(Duration timeout, List<ResilienceEvent.Listener> listeners, Clock clock)
        implements Resilient<T> {

    public Timeout {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Objects.requireNonNull(clock, "clock must not be null");
        listeners = List.copyOf(listeners);
    }

    /**
     * A {@code Timeout} with the given deadline, ready to use as-is or refine further
     * via {@code withX} methods. There is no default duration: a timeout is always an
     * explicit business decision.
     */
    public static <T> Timeout<T> of(Duration timeout) {
        return new Timeout<>(timeout, List.of(), Clock.systemClock());
    }

    public Timeout<T> withTimeout(Duration timeout) {
        return new Timeout<>(timeout, listeners, clock);
    }

    public Timeout<T> withListener(ResilienceEvent.Listener listener) {
        var newListeners = new ArrayList<>(listeners);
        newListeners.add(listener);
        return new Timeout<>(timeout, newListeners, clock);
    }

    /**
     * Use a custom {@link Clock} instead of the system clock for event timestamps.
     */
    public Timeout<T> withClock(Clock clock) {
        return new Timeout<>(timeout, listeners, clock);
    }

    @Override
    public String patternName() {
        return "timeout";
    }

    @Override
    public PatternKind patternKind() {
        return PatternKind.TIMEOUT;
    }

    @Override
    public T call(Operation<T> operation) throws ResilienciaException {
        return switch (outcome(operation)) {
            case Outcome.Success<T>(T value) -> value;
            case Outcome.TimedOut<T> timedOut -> throw new ResilienciaTimeoutException(timedOut.timeout());
            case Outcome.Failure<T>(RuntimeException cause) -> throw cause;
            case Outcome.Failure<T>(Error cause) -> throw cause;
            case Outcome.Failure<T>(Throwable cause) ->
                    throw new ResilienciaException("Operation failed within timeout", cause);
        };
    }

    @Override
    public Outcome<T> outcome(Operation<T> operation) {
        var start = clock.instant();
        var result = new AtomicReference<Outcome<T>>();
        var worker = Thread.ofVirtual().name("resiliencia-timeout").start(() -> {
            try {
                result.set(new Outcome.Success<>(operation.execute()));
            } catch (Throwable t) {
                result.set(new Outcome.Failure<>(t));
            }
        });

        boolean finished;
        try {
            finished = worker.join(timeout);
        } catch (InterruptedException e) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            return new Outcome.Failure<>(
                    new ResilienciaException("Interrupted while waiting for operation to complete", e));
        }

        if (!finished) {
            worker.interrupt();
            emit(new TimeoutEvent.TimedOut(clock.instant(), timeout));
            return new Outcome.TimedOut<>(timeout);
        }

        var outcome = result.get();
        switch (outcome) {
            case Outcome.Success<T> success ->
                    emit(new TimeoutEvent.Success(clock.instant(), Duration.between(start, clock.instant())));
            case Outcome.Failure<T> failure -> emit(new TimeoutEvent.Failed(clock.instant(), failure.cause()));
            case Outcome.TimedOut<T> timedOut -> { /* never produced by the worker */ }
        }
        return outcome;
    }

    private void emit(TimeoutEvent event) {
        for (var listener : listeners) {
            listener.onEvent(event);
        }
    }
}
