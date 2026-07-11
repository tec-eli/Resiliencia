package io.github.teceli.resiliencia.patterns.retry;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.spi.Clock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the Retry pattern MVP.
 * Demonstrates Policy + Retry + synchronous execution working together.
 */
class RetryPatternTest {
    @Test
    void should_reportRetryKind_when_patternKindQueried() {
        var retry = Retry.<String>create();

        assertThat(retry.patternKind()).isEqualTo(PatternKind.RETRY);
    }

    @Test
    void should_succeedAfterRetry_when_operationFailsInitially() {
        var counter = new AtomicInteger(0);

        var retry = Retry.<String>create()
                .withMaxAttempts(3)
                .withInitialDelay(10)
                .withShouldRetry(e -> true);

        var result = retry.call(() -> {
            var value = counter.incrementAndGet();
            if (value < 2) {
                throw new RuntimeException("Simulated failure");
            }
            return "Success on attempt " + value;
        });

        assertThat(result).isEqualTo("Success on attempt 2");
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void should_exhaustRetries_when_allAttemptsFail() {
        var counter = new AtomicInteger(0);

        var retry = Retry.<String>create()
                .withMaxAttempts(3)
                .withInitialDelay(10)
                .withShouldRetry(e -> true);

        var exception = assertThrows(RetryExhaustedException.class, () -> retry.call(() -> {
            counter.incrementAndGet();
            throw new RuntimeException("Always fails");
        }));
        assertThat(exception)
                .hasCauseInstanceOf(RuntimeException.class);
        assertThat(exception.attemptCount()).isEqualTo(3);

        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void should_throwRetryRejectedException_when_shouldRetryDeclines() {
        var counter = new AtomicInteger(0);

        // Default shouldRetry only matches IOException, so a RuntimeException is rejected outright.
        var retry = Retry.<String>create()
                .withMaxAttempts(3)
                .withInitialDelay(10);

        var exception = assertThrows(RetryRejectedException.class, () -> retry.call(() -> {
            counter.incrementAndGet();
            throw new IllegalStateException("Not retryable");
        }));
        assertThat(exception)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(exception.attemptCount()).isEqualTo(1);

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void should_emitRejectedEvent_when_shouldRetryDeclines() {
        var events = new ArrayList<RetryEvent>();

        var retry = Retry.<String>create()
                .withMaxAttempts(3)
                .withInitialDelay(10)
                .withListener(event -> {
                    if (event instanceof RetryEvent re) {
                        events.add(re);
                    }
                });

        assertThrows(RetryRejectedException.class, () -> retry.call(() -> {
            throw new IllegalStateException("Not retryable");
        }));

        assertThat(events).satisfiesExactly(
                first -> assertThat(first).isInstanceOf(RetryEvent.AttemptFailed.class),
                second -> assertThat(second).isInstanceOf(RetryEvent.Rejected.class));
    }

    @Test
    void should_returnRealFailure_when_shouldRetryThrows() {
        var retry = Retry.<String>create()
                .withMaxAttempts(3)
                .withInitialDelay(10)
                .withShouldRetry(e -> {
                    throw new RuntimeException("boom");
                });

        var outcome = retry.outcome(() -> {
            throw new IllegalStateException("real failure");
        });

        assertThat(outcome).isInstanceOfSatisfying(Outcome.Failure.class,
            failure -> assertThat(failure.cause()).isInstanceOf(IllegalStateException.class));
    }

    @Test
    void should_stopRetrying_when_overallDeadlineElapsed() {
        var clock = new RecordingClock();
        var events = new ArrayList<RetryEvent>();

        var retry = Retry.<String>create()
                .withMaxAttempts(5)
                .withInitialDelay(100)
                .withOverallDeadline(150)
                .withShouldRetry(e -> true)
                .withClock(clock)
                .withListener(event -> {
                    if (event instanceof RetryEvent re) {
                        events.add(re);
                    }
                });

        var exception = assertThrows(RetryExhaustedException.class, () -> retry.call(() -> {
            throw new RuntimeException("Always fails");
        }));

        // attempt 1 (t=0, deadline not reached) -> sleep 100 -> attempt 2 (t=100, deadline not
        // reached) -> sleep 200 -> recheck at t=300, deadline of 150 elapsed -> stop before attempt 3.
        assertThat(exception.attemptCount()).isEqualTo(2);
        assertThat(events).last().isInstanceOf(RetryEvent.Exhausted.class);
    }

    @Test
    void should_reportHasOwnDeadline_when_overallDeadlineConfigured() {
        var withDeadline = Retry.<String>create().withOverallDeadline(1_000);
        var withoutDeadline = Retry.<String>create();

        assertThat(withDeadline.hasOwnDeadline()).isTrue();
        assertThat(withoutDeadline.hasOwnDeadline()).isFalse();
    }

    @Test
    void should_emitEvents_when_retryOccurs() {
        var counter = new AtomicInteger(0);
        var events = new ArrayList<RetryEvent>();

        var retry = Retry.<String>create()
                .withMaxAttempts(3)
                .withInitialDelay(10)
                .withListener(event -> {
                    if (event instanceof RetryEvent re) {
                        events.add(re);
                    }
                })
                .withShouldRetry(e -> true);

        retry.call(() -> {
            var value = counter.incrementAndGet();
            if (value < 2) {
                throw new RuntimeException("Fail attempt " + value);
            }
            return "Success";
        });

        assertThat(events).satisfiesExactly(
                first -> assertThat(first).isInstanceOf(RetryEvent.AttemptFailed.class),
                second -> assertThat(second).isInstanceOf(RetryEvent.Success.class));
    }

    @Test
    void should_returnOutcome_when_usingOutcomeMethod() {
        var counter = new AtomicInteger(0);

        var retry = Retry.<String>create()
                .withMaxAttempts(3)
                .withInitialDelay(10)
                .withShouldRetry(e -> true);

        var outcome = retry.outcome(() -> {
            var value = counter.incrementAndGet();
            if (value < 2) {
                throw new RuntimeException("Fail first attempt");
            }
            return "Success";
        });

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Success.class, s ->
                        assertThat(s.value()).isEqualTo("Success"));
    }

    @Test
    void should_returnFailureWithOriginalCause_when_usingOutcomeMethod() {
        var retry = Retry.<String>create()
                .withMaxAttempts(2)
                .withInitialDelay(10)
                .withShouldRetry(e -> true);

        var cause = new RuntimeException("Always fails");
        var outcome = retry.outcome(() -> {
            throw cause;
        });

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Failure.class, f ->
                        assertThat(f.cause()).isSameAs(cause));
    }

    @Test
    void should_respectBackoffMultiplier_when_configured() {
        // Asserts relative growth instead of absolute delays to avoid CI timing flakiness.
        var attemptTimestamps = new ArrayList<Long>();

        var retry = Retry.<String>create()
                .withMaxAttempts(4)
                .withInitialDelay(10)
                .withBackoffMultiplier(2.0)
                .withShouldRetry(e -> true);

        try {
            retry.call(() -> {
                attemptTimestamps.add(System.nanoTime());
                throw new RuntimeException("Always fails");
            });
        } catch (Exception ignored) {
            // Exhausted after 4 attempts; only the timing between attempts matters here.
        }

        assertThat(attemptTimestamps).hasSize(4);

        var delaysMs = new ArrayList<Long>();
        for (var i = 1; i < attemptTimestamps.size(); i++) {
            delaysMs.add((attemptTimestamps.get(i) - attemptTimestamps.get(i - 1)) / 1_000_000);
        }

        // Expected delays are ~10ms, ~20ms, ~40ms; assert the increasing trend rather than
        // absolute wall-clock bounds, since exact timing is not guaranteed under CI load.
        assertThat(delaysMs.get(1)).isGreaterThan(delaysMs.get(0));
        assertThat(delaysMs.get(2)).isGreaterThan(delaysMs.get(1));
    }

    @Test
    void should_capBackoffDelays_when_maxDelayConfigured() {
        var clock = new RecordingClock();
        var retry = Retry.<String>create()
                .withMaxAttempts(4)
                .withInitialDelay(100)
                .withBackoffMultiplier(10.0)
                .withMaxDelay(250)
                .withShouldRetry(e -> true)
                .withClock(clock);

        retry.outcome(() -> {
            throw new RuntimeException("Always fails");
        });

        assertThat(clock.sleeps).containsExactly(100L, 250L, 250L);
    }

    @Test
    void should_randomizeDelayWithinBounds_when_jitterConfigured() {
        var clock = new RecordingClock();
        var retry = Retry.<String>create()
                .withMaxAttempts(2)
                .withInitialDelay(1_000)
                .withJitter(0.5)
                .withShouldRetry(e -> true)
                .withClock(clock);

        retry.outcome(() -> {
            throw new RuntimeException("Always fails");
        });

        assertThat(clock.sleeps).hasSize(1);
        assertThat(clock.sleeps.getFirst()).isBetween(500L, 1_500L);
    }

    @Test
    void should_neverExceedMaxDelay_when_jitterAndMaxDelayCombined() {
        var clock = new RecordingClock();
        var retry = Retry.<String>create()
                .withMaxAttempts(5)
                .withInitialDelay(100)
                .withJitter(1.0)
                .withMaxDelay(100)
                .withShouldRetry(e -> true)
                .withClock(clock);

        retry.outcome(() -> {
            throw new RuntimeException("Always fails");
        });

        assertThat(clock.sleeps).hasSize(4).allSatisfy(sleep ->
                assertThat(sleep).isBetween(0L, 100L));
    }

    @Test
    void should_rejectInvalidJitterAndMaxDelay_when_configured() {
        assertThatIllegalArgumentException()
            .isThrownBy(() ->
                Retry.<String>create().withJitter(-0.1));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> Retry.<String>create().withJitter(1.1));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> Retry.<String>create().withMaxDelay(-1));
    }

    @Test
    void should_rejectNegativeOverallDeadline_when_configured() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> Retry.<String>create().withOverallDeadline(-1));
    }

    @Test
    void should_returnOperationResult_when_listenerThrowsException() {
        var retry = Retry.<String>create()
                .withListener(event -> {
                    throw new IllegalStateException("listener boom");
                });

        assertThat(retry.call(() -> "done")).isEqualTo("done");
    }

    @Test
    void should_neverRetry_when_maxAttemptsIsOne_evenForARetryableException() {
        var counter = new AtomicInteger(0);
        var retry = Retry.<String>create()
                .withMaxAttempts(1)
                .withShouldRetry(e -> true);

        var exception = assertThrows(RetryExhaustedException.class, () -> retry.call(() -> {
            counter.incrementAndGet();
            throw new RuntimeException("Always fails");
        }));

        assertThat(exception.attemptCount()).isEqualTo(1);
        assertThat(counter.get())
                .as("maxAttempts(1) disables retrying entirely, even though shouldRetry allows it")
                .isEqualTo(1);
    }

    @Test
    void should_stopAtWhicheverComesFirst_when_overallDeadlineJitterAndMaxDelayAllConfigured() {
        var clock = new RecordingClock();
        var retry = Retry.<String>create()
                .withMaxAttempts(10)
                .withInitialDelay(100)
                .withBackoffMultiplier(2.0)
                .withJitter(0.5)
                .withMaxDelay(150)
                .withOverallDeadline(300)
                .withShouldRetry(e -> true)
                .withClock(clock);

        var exception = assertThrows(RetryExhaustedException.class, () -> retry.call(() -> {
            throw new RuntimeException("Always fails");
        }));

        // Every recorded sleep must still respect the maxDelay cap despite jitter, and the loop
        // must stop once the overall deadline is exceeded rather than running all 10 attempts.
        assertThat(clock.sleeps).allSatisfy(sleep -> assertThat(sleep).isBetween(0L, 150L));
        assertThat(exception.attemptCount()).isLessThan(10);
    }

    @Test
    void should_throwNullPointerException_when_listenerIsNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> Retry.<String>create().withListener(null));
    }

    @Test
    void should_preserveRealCauseAndInterruptFlag_when_interruptedDuringBackoff() {
        var cause = new RuntimeException("Always fails");
        var retry = Retry.<String>create()
                .withMaxAttempts(3)
                .withInitialDelay(10)
                .withShouldRetry(e -> true)
                .withClock(new InterruptingClock());

        var outcome = retry.outcome(() -> {
            throw cause;
        });

        assertThat(outcome).isInstanceOfSatisfying(Outcome.Failure.class, f ->
                assertThat(f.cause()).isSameAs(cause));
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void should_throwRetryInterruptedException_when_interruptedDuringBackoff() {
        var cause = new RuntimeException("Always fails");
        var events = new ArrayList<RetryEvent>();
        var retry = Retry.<String>create()
                .withMaxAttempts(3)
                .withInitialDelay(10)
                .withShouldRetry(e -> true)
                .withClock(new InterruptingClock())
                .withListener(event -> {
                    if (event instanceof RetryEvent re) {
                        events.add(re);
                    }
                });

        var exception = assertThrows(RetryInterruptedException.class, () -> retry.call(() -> {
            throw cause;
        }));

        assertThat(exception).hasCause(cause);
        assertThat(exception.attemptCount()).isEqualTo(1);
        assertThat(Thread.interrupted()).isTrue();
        assertThat(events).last().isInstanceOfSatisfying(RetryEvent.Interrupted.class, interrupted ->
                assertThat(interrupted.lastError()).isSameAs(cause));
    }

    @Test
    void should_throwRetryRejectedException_when_shouldRetryDeclinesOnLastAttempt() {
        var counter = new AtomicInteger(0);

        // shouldRetry is evaluated before the attempt-count check, per its documented contract:
        // a non-retryable exception on the last attempt is still Rejected, not Exhausted.
        var retry = Retry.<String>create()
                .withMaxAttempts(1)
                .withInitialDelay(10);

        var exception = assertThrows(RetryRejectedException.class, () -> retry.call(() -> {
            counter.incrementAndGet();
            throw new IllegalStateException("Not retryable");
        }));
        assertThat(exception)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(exception.attemptCount()).isEqualTo(1);

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void should_rejectInvalidMaxAttemptsInitialDelayAndBackoffMultiplier_when_configured() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> Retry.<String>create().withMaxAttempts(0));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> Retry.<String>create().withInitialDelay(-1));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> Retry.<String>create().withBackoffMultiplier(0.9));
    }

    @Test
    void should_rejectNullShouldRetryAndClock_when_configured() {
        assertThatNullPointerException()
            .isThrownBy(() -> Retry.<String>create().withShouldRetry(null));
        assertThatNullPointerException()
            .isThrownBy(() -> Retry.<String>create().withClock(null));
    }

    /**
     * Deterministic clock recording each requested sleep instead of blocking, so backoff
     * math can be asserted exactly and instantly.
     */
    private static final class RecordingClock implements Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public synchronized Instant instant() {
            return now;
        }

        @Override
        public synchronized void sleep(long millis) {
            sleeps.add(millis);
            now = now.plusMillis(millis);
        }
    }

    /**
     * Simulates a thread interrupt arriving during backoff, without actually blocking the test.
     */
    private static final class InterruptingClock implements Clock {
        @Override
        public Instant instant() {
            return Instant.parse("2026-01-01T00:00:00Z");
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            throw new InterruptedException("Simulated interrupt during backoff");
        }
    }
}
