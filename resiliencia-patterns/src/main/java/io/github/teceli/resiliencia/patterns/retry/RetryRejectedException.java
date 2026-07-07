package io.github.teceli.resiliencia.patterns.retry;

import io.github.teceli.resiliencia.core.api.ResilienciaException;

import java.io.Serial;

/**
 * Thrown by the Retry pattern when {@code shouldRetry} declines to retry a failure before the
 * attempt budget is exhausted. Distinct from {@link RetryExhaustedException}, which means the
 * attempt budget itself ran out. The rejected failure is available via {@link #getCause()}.
 */
public final class RetryRejectedException extends ResilienciaException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int attemptCount;

    public RetryRejectedException(int attemptCount, Throwable cause) {
        super("Retry rejected after " + attemptCount + " attempt(s): shouldRetry declined to retry "
                + cause.getClass().getSimpleName(), cause);
        this.attemptCount = attemptCount;
    }

    /**
     * Total number of attempts made before {@code shouldRetry} declined to retry, including the
     * first call.
     */
    public int attemptCount() {
        return attemptCount;
    }
}
