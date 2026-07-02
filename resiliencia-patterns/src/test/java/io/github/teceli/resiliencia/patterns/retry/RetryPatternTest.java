package io.github.teceli.resiliencia.patterns.retry;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
                .withInitialDelay(10);

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
                .withInitialDelay(10);

        assertThatExceptionOfType(RetryExhaustedException.class)
                .isThrownBy(() -> retry.call(() -> {
                    counter.incrementAndGet();
                    throw new RuntimeException("Always fails");
                }))
                .withCauseInstanceOf(RuntimeException.class)
                .extracting(RetryExhaustedException::attemptCount)
                .isEqualTo(3);

        assertThat(counter.get()).isEqualTo(3);
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
                });

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
                .withInitialDelay(10);

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
                .withInitialDelay(10);

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
                .withBackoffMultiplier(2.0);

        try {
            retry.call(() -> {
                attemptTimestamps.add(System.nanoTime());
                throw new RuntimeException("Always fails");
            });
        } catch (Exception ignored) {
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
}
