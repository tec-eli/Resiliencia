package io.github.teceli.resiliencia.core.api;

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
    T call(Operation<T> operation) throws ResilienciaException;

    /**
     * Execute an operation and capture the result or failure as an Outcome.
     * Never throws an exception — always returns Success, Failure, or a pattern-specific outcome.
     */
    Outcome<T> outcome(Operation<T> operation);

    /**
     * Execute an operation asynchronously and return a handle to its result.
     *
     * TODO: not yet implemented. Intended to run on a virtual thread
     * "Virtual threads as the foundation") and to integrate with structured concurrency
     * once Timeout/Bulkhead/RateLimiter exist, so cancellation composes correctly across
     * a Policy chain. Implement once those patterns land; until then callers must use
     * call()/outcome() from the calling thread.
     */
    default CompletableFuture<T> callAsync(Operation<T> operation) {
        throw new UnsupportedOperationException("callAsync is not yet implemented; see TODO in Resilient");
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

    @FunctionalInterface
    interface Operation<T> {
        T execute() throws ResilienciaException;
    }
}
