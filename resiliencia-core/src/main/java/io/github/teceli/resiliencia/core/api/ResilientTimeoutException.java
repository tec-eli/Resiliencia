package io.github.teceli.resiliencia.core.api;

import java.io.Serial;
import java.time.Duration;
import java.util.Objects;

/**
 * Thrown when an operation does not complete within the configured timeout.
 * The operation's thread has been interrupted by the time this is thrown; whether the
 * operation actually stopped depends on it responding to interruption.
 */
public final class ResilientTimeoutException extends ResilientException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Duration timeout;

    public ResilientTimeoutException(Duration timeout) {
        super("Operation timed out after " + Objects.requireNonNull(timeout, "timeout must not be null"));
        this.timeout = timeout;
    }

    /**
     * The configured timeout that was exceeded.
     */
    public Duration timeout() {
        return timeout;
    }
}
