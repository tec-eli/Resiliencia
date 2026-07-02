package io.github.teceli.resiliencia.core.api;

import java.io.Serial;
import java.time.Duration;

/**
 * Thrown when an operation does not complete within the configured timeout.
 * The operation's thread has been interrupted by the time this is thrown; whether the
 * operation actually stopped depends on it responding to interruption.
 */
public final class ResilienciaTimeoutException extends ResilienciaException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Duration timeout;

    public ResilienciaTimeoutException(Duration timeout) {
        super("Operation timed out after " + timeout);
        this.timeout = timeout;
    }

    /**
     * The configured timeout that was exceeded.
     */
    public Duration timeout() {
        return timeout;
    }
}
