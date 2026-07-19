package io.github.teceli.resiliencia.patterns.ratelimiter;

import io.github.teceli.resiliencia.core.api.ResilientException;

import java.io.Serial;
import java.time.Duration;

/**
 * Thrown when a RateLimiter rejects a call because the current window's permits are used up
 * and no permit would become available within the configured maximum wait time.
 *
 * <p>The {@code name} carried by this exception is the rate limiter's own name, which must be a
 * static, compile-time-known string — never request-derived or tenant-derived. It is used as a
 * cardinality-bounded key/tag in metrics and logging; unbounded distinct names would cause
 * unbounded memory growth in metrics backends.
 */
public final class RateLimiterException extends ResilientException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String name;
    private final int limit;
    private final Duration period;
    private final Duration maxWait;

    public RateLimiterException(String name, int limit, Duration period, Duration maxWait) {
        super("Rate limiter '" + name + "' exceeded: " + limit + " calls per " + period
                + (maxWait.isZero() ? "" : ", no permit would become available within " + maxWait));
        this.name = name;
        this.limit = limit;
        this.period = period;
        this.maxWait = maxWait;
    }

    /**
     * The rate limiter instance name.
     */
    public String name() {
        return name;
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
