package io.github.teceli.resiliencia.compose;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.ResilienciaException;
import io.github.teceli.resiliencia.core.api.ResilienciaTimeoutException;
import io.github.teceli.resiliencia.patterns.retry.Retry;
import io.github.teceli.resiliencia.patterns.timeout.Timeout;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the real Timeout pattern composed in a Policy,
 * including the optimum per-attempt ordering: Retry outside, Timeout inside.
 */
class PolicyTimeoutTest {

    @Test
    void should_throwResilienciaTimeoutException_when_composedOperationExceedsTimeout() {
        var policy = Policy.compose(Timeout.<String>of(Duration.ofMillis(50)));

        assertThrows(ResilienciaTimeoutException.class, () ->
            policy.call(PolicyTimeoutTest::blockUntilInterrupted));
    }

    @Test
    void should_returnTimedOutOutcome_when_composedOperationExceedsTimeout() {
        var policy = Policy.compose(Timeout.<String>of(Duration.ofMillis(50)));

        var outcome = policy.outcome(PolicyTimeoutTest::blockUntilInterrupted);

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.TimedOut.class, t ->
                        assertThat(t.timeout()).isEqualTo(Duration.ofMillis(50)));
    }

    @Test
    void should_retryAfterPerAttemptTimeout_when_retryWrapsTimeout() {
        var attempts = new AtomicInteger(0);

        var retry = Retry.<String>create().withMaxAttempts(3).withInitialDelay(10).withShouldRetry(e -> true);
        var timeout = Timeout.<String>of(Duration.ofMillis(100));
        var policy = Policy.compose(retry).and(timeout);

        var result = policy.call(() -> {
            if (attempts.incrementAndGet() == 1) {
                return blockUntilInterrupted();
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void should_exhaustRetries_when_everyAttemptTimesOut() {
        var attempts = new AtomicInteger(0);

        var retry = Retry.<String>create().withMaxAttempts(2).withInitialDelay(10).withShouldRetry(e -> true);
        var timeout = Timeout.<String>of(Duration.ofMillis(50));
        var policy = Policy.compose(retry).and(timeout);

        var exception = assertThrows(ResilienciaException.class, () -> policy.call(() -> {
            attempts.incrementAndGet();
            return blockUntilInterrupted();
        }));
        assertThat(exception).hasCauseInstanceOf(ResilienciaTimeoutException.class);

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void should_interruptOperationThroughChain_when_asyncCallCancelled() throws Exception {
        var operationStarted = new CountDownLatch(1);
        var operationInterrupted = new CountDownLatch(1);
        var retry = Retry.<String>create().withMaxAttempts(3).withInitialDelay(10).withShouldRetry(e -> true);
        var timeout = Timeout.<String>of(Duration.ofSeconds(30));
        var policy = Policy.compose(retry).and(timeout);

        var future = policy.callAsync(() -> {
            operationStarted.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(30));
                return "never";
            } catch (InterruptedException e) {
                operationInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new ResilienciaException("interrupted", e);
            }
        });
        assertThat(operationStarted.await(5, TimeUnit.SECONDS)).isTrue();

        future.cancel(true);

        assertThat(operationInterrupted.await(5, TimeUnit.SECONDS))
                .as("cancellation should interrupt the operation through Retry and Timeout")
                .isTrue();
        assertThat(future.isCancelled()).isTrue();
    }

    /**
     * Blocks until interrupted by the timeout; converts the interrupt into an unchecked
     * exception as any interruption-aware operation would.
     */
    private static String blockUntilInterrupted() {
        try {
            Thread.sleep(Duration.ofSeconds(30));
            return "never";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResilienciaException("interrupted", e);
        }
    }
}
