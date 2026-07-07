package io.github.teceli.resiliencia.patterns.bulkhead;

import io.github.teceli.resiliencia.core.api.ResilienciaException;

import java.io.Serial;
import java.time.Duration;

/**
 * Thrown when a Bulkhead rejects a call because all permits are in use and no permit
 * became available within the configured maximum wait time.
 */
public final class BulkheadFullException extends ResilienciaException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String name;
    private final int maxConcurrentCalls;
    private final Duration maxWait;

    public BulkheadFullException(String name, int maxConcurrentCalls, Duration maxWait) {
        super("Bulkhead '" + name + "' is full: " + maxConcurrentCalls + " concurrent calls in flight"
                + (maxWait.isZero() ? "" : ", no permit became available within " + maxWait));
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
