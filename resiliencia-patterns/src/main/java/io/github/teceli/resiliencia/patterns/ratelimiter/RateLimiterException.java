package io.github.teceli.resiliencia.patterns.ratelimiter;

import io.github.teceli.resiliencia.core.api.ResilienciaException;

import java.io.Serial;
import java.time.Duration;

/**
 * Thrown when a RateLimiter rejects a call because the current window's permits are used up
 * and no permit would become available within the configured maximum wait time.
 */
public final class RateLimiterException extends ResilienciaException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int limit;
    private final Duration period;
    private final Duration maxWait;

    public RateLimiterException(int limit, Duration period, Duration maxWait) {
        super("Rate limit exceeded: " + limit + " calls per " + period
                + (maxWait.isZero() ? "" : ", no permit would become available within " + maxWait));
        this.limit = limit;
        this.period = period;
        this.maxWait = maxWait;
    }

    /**
     * The number of calls permitted per window.
     */
    public int limit() {
        return limit;
    }

    /**
     * The window length.
     */
    public Duration period() {
        return period;
    }

    /**
     * How long the call was allowed to wait for a permit before being rejected
     * (zero means fail-fast).
     */
    public Duration maxWait() {
        return maxWait;
    }
}
