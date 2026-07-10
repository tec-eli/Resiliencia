package io.github.teceli.resiliencia.patterns.retry;

import io.github.teceli.resiliencia.core.api.ResilientException;

import java.io.Serial;

/**
 * Thrown by the Retry pattern when the thread is interrupted while waiting for a backoff delay
 * between attempts. Distinct from {@link RetryExhaustedException} (the attempt budget ran out) and
 * {@link RetryRejectedException} ({@code shouldRetry} declined to retry): interruption stops the
 * loop for a reason unrelated to either. The cause is the last failure that was about to be
 * retried, not the interrupt itself — the interrupt is a control signal, not a failure. The
 * thread's interrupt status is restored before this exception is thrown.
 */
public final class RetryInterruptedException extends ResilientException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int attemptCount;

    public RetryInterruptedException(int attemptCount, Throwable cause) {
        super("Retry interrupted during backoff after " + attemptCount + " attempt(s)", cause);
        this.attemptCount = attemptCount;
    }

    /**
     * Total number of attempts made before the interrupt arrived, including the first call.
     */
    public int attemptCount() {
        return attemptCount;
    }
}
