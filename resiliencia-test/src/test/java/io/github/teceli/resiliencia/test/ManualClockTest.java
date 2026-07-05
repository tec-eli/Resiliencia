package io.github.teceli.resiliencia.test;

import io.github.teceli.resiliencia.patterns.retry.Retry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ManualClockTest {

    @Test
    void should_startAtGivenInstant_when_createdWithStartingAt() {
        var start = Instant.parse("2026-07-01T12:00:00Z");

        var clock = ManualClock.startingAt(start);

        assertThat(clock.instant()).isEqualTo(start);
    }

    @Test
    void should_moveForward_when_advanced() {
        var clock = ManualClock.create();
        var before = clock.instant();

        clock.advance(Duration.ofSeconds(5));

        assertThat(clock.instant()).isEqualTo(before.plusSeconds(5));
    }

    @Test
    void should_advanceInsteadOfBlocking_when_sleepCalled() {
        var clock = ManualClock.create();
        var before = clock.instant();

        clock.sleep(250);

        assertThat(clock.instant()).isEqualTo(before.plusMillis(250));
    }

    @Test
    void should_rejectNegativeDuration_when_advanced() {
        var clock = ManualClock.create();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> clock.advance(Duration.ofMillis(-1)));
    }

    @Test
    void should_makeRetryBackoffInstant_when_pluggedIntoRetry() {
        var clock = ManualClock.create();
        var attempts = new AtomicInteger(0);
        var retry = Retry.<String>create()
                .withMaxAttempts(3)
                .withInitialDelay(60_000)
                .withClock(clock);

        var result = retry.call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("Simulated failure");
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        // Both one-minute backoffs elapsed on the manual clock, not on the wall clock.
        assertThat(clock.instant())
                .isEqualTo(ManualClock.create().instant().plus(Duration.ofMinutes(3)));
    }
}
