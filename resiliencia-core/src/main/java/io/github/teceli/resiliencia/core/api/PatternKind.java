package io.github.teceli.resiliencia.core.api;

public enum PatternKind {
    RETRY,
    TIMEOUT,
    CIRCUIT_BREAKER,
    BULKHEAD,
    RATE_LIMITER,
    CUSTOM
}
