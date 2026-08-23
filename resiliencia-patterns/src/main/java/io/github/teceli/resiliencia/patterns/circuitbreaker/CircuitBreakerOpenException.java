package io.github.teceli.resiliencia.patterns.circuitbreaker;

import io.github.teceli.resiliencia.core.api.ResilientException;

import java.io.Serial;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Thrown when a call is rejected because the circuit breaker will not let it through: either
 * the circuit is Open, or it is HalfOpen and every permitted test call has already been issued.
 * {@code openSince} and {@code remainingWait} are only present in the Open case — a HalfOpen
 * rejection has nothing equivalent to report, since the circuit is already attempting test calls.
 *
 * <p>The {@code name} carried by this exception is the circuit breaker's own name, which must be a
 * static, compile-time-known string — never request-derived or tenant-derived. It is used as a
 * cardinality-bounded key/tag in metrics and logging; unbounded distinct names would cause
 * unbounded memory growth in metrics backends.
 */
public final class CircuitBreakerOpenException extends ResilientException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String name;
    private final Instant openSince;
    private final Duration remainingWait;

    private CircuitBreakerOpenException(String message, String name, Instant openSince, Duration remainingWait) {
        super(message);
        this.name = name;
        this.openSince = openSince;
        this.remainingWait = remainingWait;
    }

    /**
     * A rejection while the circuit is Open, carrying when it opened and how much longer it
     * will stay Open before a HalfOpen test call is attempted.
     */
    public static CircuitBreakerOpenException forOpenState(String name, Instant openSince, Duration remainingWait) {
        return new CircuitBreakerOpenException(
            "Circuit breaker '" + name + "' is open, remaining wait " + remainingWait,
            name, openSince, remainingWait);
    }

    /**
     * A rejection while the circuit is HalfOpen, because every permitted test call has already
     * been issued.
     */
    public static CircuitBreakerOpenException forHalfOpenState(String name) {
        return new CircuitBreakerOpenException(
            "Circuit breaker '" + name + "' is half-open with no test-call permits left",
            name, null, null);
    }

    public String name() {
        return name;
    }

    public Optional<Instant> openSince() {
        return Optional.ofNullable(openSince);
    }

    public Optional<Duration> remainingWait() {
        return Optional.ofNullable(remainingWait);
    }
}
