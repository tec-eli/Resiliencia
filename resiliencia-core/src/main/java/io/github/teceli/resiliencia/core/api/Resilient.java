package io.github.teceli.resiliencia.core.api;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Base interface for all resilience patterns.
 * Patterns implement this to provide call execution with resilience guarantees.
 */
public interface Resilient<T> {
    /**
     * Execute an operation with resilience guarantees.
     * May throw ResilienciaException or a specific pattern exception.
     */
    T call(Operation<T> operation) throws ResilientException;

    /**
     * Execute an operation and capture the result or failure as an Outcome.
     * Never throws for a recorded {@code Exception} — always returns Success, Failure, or a
     * pattern-specific outcome. An {@code Error} thrown by the operation propagates uncaught
     * instead of being captured as a Failure: fatal JVM conditions (e.g. {@code OutOfMemoryError})
     * should not be treated as a recoverable result.
     */
    Outcome<T> outcome(Operation<T> operation);

    /**
     * Execute an operation asynchronously on a new virtual thread and return a handle to its
     * result. The future completes with the value of {@link #call}, or exceptionally with
     * whatever {@link #call} throws. If {@link #call} lets an {@code Error} propagate, the future
     * is still completed exceptionally with it (so a waiter on the future is not left hanging),
     * but the {@code Error} is also rethrown on the worker thread afterward — it is never treated
     * as a recoverable business outcome (see "Error handling" in {@code docs/architecture/ARCHITECTURE.md}).
     *
     * Cancelling the returned future interrupts the virtual thread, so cancellation propagates
     * through whichever pattern is currently blocking — a Timeout wait, a Retry backoff, a
     * Bulkhead or RateLimiter permit wait — including across a full Policy chain.
     */
    default CompletableFuture<T> callAsync(Operation<T> operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        var future = new CompletableFuture<T>();
        var worker = Thread.ofVirtual().name("resiliencia-async").start(() -> {
            try {
                future.complete(call(operation));
            } catch (Exception e) {
                future.completeExceptionally(e);
            } catch (Error e) {
                // Not treated as a business outcome (see "Error handling" in
                // docs/architecture/ARCHITECTURE.md): the future is still completed so any
                // waiter is unblocked, but the Error is rethrown afterward instead of being
                // silently absorbed, so it still propagates uncaught on this worker thread.
                future.completeExceptionally(e);
                throw e;
            }
        });
        future.whenComplete((result, error) -> {
            if (future.isCancelled()) {
                worker.interrupt();
            }
        });
        return future;
    }

    /**
     * The name of this pattern, e.g. "retry", "timeout", "circuit-breaker".
     * Used for identification (e.g. by Policy) without coupling to concrete pattern types.
     * Defaults to "custom" for user-defined Resilient implementations.
     */
    default String patternName() {
        return "custom";
    }

    /**
     * The kind of this pattern, used for internal comparisons (e.g. Policy order validation).
     * Unlike {@link #patternName()}, which is a free-form observability label, this is a closed
     * enum the library can reason about exhaustively.
     * Defaults to {@link PatternKind#CUSTOM} for user-defined Resilient implementations.
     */
    default PatternKind patternKind() {
        return PatternKind.CUSTOM;
    }

    /**
     * Whether this pattern already enforces its own upper bound on total duration, independent
     * of any outer Timeout. Used by Policy to decide whether a Timeout-wraps-Retry ordering
     * warning still applies. Defaults to false for user-defined Resilient implementations.
     */
    default boolean hasOwnDeadline() {
        return false;
    }

    @FunctionalInterface
    interface Operation<T> {
        T execute() throws ResilientException;
    }
}
