package io.github.teceli.resiliencia.core.api;

/**
 * The kind of a resilience pattern, used for internal identity checks — e.g. Policy order
 * validation — without coupling to concrete pattern types.
 *
 * This is not an observability concept: human-readable identification (events, metrics, logs)
 * uses {@link Resilient#patternName()} instead.
 */
public enum PatternKind {
    RETRY,
    TIMEOUT,
    CIRCUIT_BREAKER,
    BULKHEAD,
    RATE_LIMITER,
    /**
     * A user-defined {@link Resilient} implementation that is none of the built-in patterns.
     * Order validation makes no assumptions about custom patterns.
     */
    CUSTOM
}
