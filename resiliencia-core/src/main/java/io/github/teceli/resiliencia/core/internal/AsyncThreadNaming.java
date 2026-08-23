package io.github.teceli.resiliencia.core.internal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates a unique name for each async worker virtual thread, so thread dumps under load can
 * be correlated back to a specific {@code callAsync} invocation.
 * Package-private and not exported: obtain names via {@link #next()}.
 */
public final class AsyncThreadNaming {

    private static final AtomicLong COUNTER = new AtomicLong();

    private AsyncThreadNaming() {}

    public static String next() {
        return "resiliencia-async-" + COUNTER.incrementAndGet();
    }
}
