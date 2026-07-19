package io.github.teceli.resiliencia.patterns.timeout;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates a unique name for each Timeout worker virtual thread, so thread dumps under load can
 * be correlated back to a specific {@link Timeout#outcome} invocation.
 * Package-private and not exported: obtain names via {@link #next()}.
 */
final class TimeoutThreadNaming {

    private static final AtomicLong COUNTER = new AtomicLong();

    private TimeoutThreadNaming() {}

    static String next() {
        return "resiliencia-timeout-" + COUNTER.incrementAndGet();
    }
}
