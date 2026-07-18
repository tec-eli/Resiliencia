package io.github.teceli.resiliencia.core.internal;

import io.github.teceli.resiliencia.core.spi.Clock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SystemClock}, the default {@link Clock} backed by the system wall clock
 * and {@link Thread#sleep(long)}.
 */
class SystemClockTest {

    @Test
    void should_returnCurrentWallClockTime_when_instantQueried() {
        var before = Instant.now();

        var reported = Clock.systemClock().instant();

        var after = Instant.now();
        assertThat(reported).isBetween(before, after);
    }

    @Test
    void should_blockForApproximatelyRequestedDuration_when_sleeping() throws InterruptedException {
        var clock = Clock.systemClock();
        var start = System.nanoTime();

        clock.sleep(50);

        var elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(50);
    }

    @Test
    void should_throwInterruptedException_when_threadInterruptedWhileSleeping() throws Exception {
        var clock = Clock.systemClock();
        var sleeping = new CountDownLatch(1);
        var interruptedException = new AtomicReference<Exception>();

        var worker = Thread.ofVirtual().start(() -> {
            try {
                sleeping.countDown();
                clock.sleep(30_000);
            } catch (InterruptedException e) {
                interruptedException.set(e);
            }
        });

        assertThat(sleeping.await(5, TimeUnit.SECONDS)).isTrue();
        worker.interrupt();
        worker.join(Duration.ofSeconds(5).toMillis());

        assertThat(interruptedException.get()).isInstanceOf(InterruptedException.class);
    }
}
