package io.github.teceli.resiliencia.patterns.retry;

import io.github.teceli.resiliencia.core.api.ResilientException;

import java.io.Serial;

/**
 * Thrown by the Retry pattern when all configured attempts have failed.
 * Carries the total number of attempts made; the last failure is available via {@link #getCause()}.
 */
public final class RetryExhaustedException extends ResilientException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int attemptCount;

    public RetryExhaustedException(int attemptCount, Throwable cause) {
        super("Retry exhausted after " + attemptCount + " attempt(s)", cause);
        this.attemptCount = attemptCount;
    }

    /**
     * Total number of attempts made before giving up, including the first call.
     */
    public int attemptCount() {
        return attemptCount;
    }
}
