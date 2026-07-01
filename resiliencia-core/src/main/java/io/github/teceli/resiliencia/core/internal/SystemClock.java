package io.github.teceli.resiliencia.core.internal;

import io.github.teceli.resiliencia.core.spi.Clock;

import java.time.Instant;

/**
 * Default {@link Clock} implementation, backed by the system wall clock.
 * Package-private and not exported: obtain it via {@link Clock#systemClock()}.
 */
public final class SystemClock implements Clock {

    public static final Clock INSTANCE = new SystemClock();

    private SystemClock() {}

    @Override
    public Instant instant() {
        return Instant.now();
    }

    @Override
    public void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
