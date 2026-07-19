package io.github.teceli.resiliencia.patterns.bulkhead;

import io.github.teceli.resiliencia.core.api.ResilientException;

import java.io.Serial;
import java.time.Duration;
import java.util.Objects;

/**
 * Thrown when a Bulkhead rejects a call because all permits are in use and no permit
 * became available within the configured maximum wait time.
 *
 * <p>The {@code name} carried by this exception is the bulkhead's own name, which must be a
 * static, compile-time-known string — never request-derived or tenant-derived. It is used as a
 * cardinality-bounded key/tag in metrics and logging; unbounded distinct names would cause
 * unbounded memory growth in metrics backends.
 */
public final class BulkheadFullException extends ResilientException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String name;
    private final int maxConcurrentCalls;
    private final Duration maxWait;

    public BulkheadFullException(String name, int maxConcurrentCalls, Duration maxWait) {
        super("Bulkhead '" + Objects.requireNonNull(name, "name must not be null") + "' is full: "
                + maxConcurrentCalls + " concurrent calls in flight"
                + (Objects.requireNonNull(maxWait, "maxWait must not be null").isZero()
                        ? "" : ", no permit became available within " + maxWait));
        this.name = name;
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.maxWait = maxWait;
    }

    /**
     * The bulkhead instance name.
     */
    public String name() {
        return name;
    }

    /**
     * The concurrency limit that was reached.
     */
    public int maxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    /**
     * How long the call waited for a permit before being rejected (zero means fail-fast).
     */
    public Duration maxWait() {
        return maxWait;
    }
}
