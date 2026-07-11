package io.github.teceli.resiliencia.compose;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.ResilientException;
import io.github.teceli.resiliencia.core.api.ResilientTimeoutException;
import io.github.teceli.resiliencia.patterns.bulkhead.Bulkhead;
import io.github.teceli.resiliencia.patterns.bulkhead.BulkheadEvent;
import io.github.teceli.resiliencia.patterns.ratelimiter.RateLimiter;
import io.github.teceli.resiliencia.patterns.retry.Retry;
import io.github.teceli.resiliencia.patterns.retry.RetryEvent;
import io.github.teceli.resiliencia.patterns.retry.RetryInterruptedException;
import io.github.teceli.resiliencia.patterns.timeout.Timeout;
import io.github.teceli.resiliencia.patterns.timeout.TimeoutEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for issue #72: interrupt propagation when {@code Timeout} is composed
 * outermost around Retry, Bulkhead, or RateLimiter — the "Timeout wraps X" ordering flagged as a
 * {@code WARN} (construction still proceeds) by {@code Policy}'s order validation, documented in
 * {@code docs/architecture/compose/policy.md}.
 *
 * <p>In this ordering, {@code Policy.compose(timeout).and(inner)} builds
 * {@code timeout.call(() -> inner.call(operation))}: the entire inner pattern — including its own
 * blocking waits (Retry's backoff sleep, a Bulkhead permit wait, a RateLimiter window wait) — runs
 * on the single virtual thread {@link Timeout} spawns and monitors (see {@code Timeout#outcome}).
 * When the deadline passes before that thread finishes, {@code Timeout} interrupts it — a real
 * interrupt, not polling (timeout.md, "Cancellation is real").
 *
 * <p>These tests confirm two things that hold regardless of which pattern is innermost:
 * <ol>
 *   <li>The interrupt is not swallowed: it genuinely reaches whatever the inner pattern is
 *       blocked on, and that inner pattern reports it through its own normal interrupted-path
 *       behavior (a distinct exception/event for Retry; a wrapped {@link InterruptedException}
 *       for Bulkhead/RateLimiter) — never silently, and never misreported as a different failure
 *       kind (e.g. a Retry exhaustion instead of an interruption).</li>
 *   <li>Regardless of what the abandoned inner pattern eventually does with that interrupt, the
 *       caller always gets exactly {@code Timeout}'s own contract: an immediate
 *       {@link ResilientTimeoutException} / {@link Outcome.TimedOut} once the deadline passes
 *       (timeout.md: "the calling thread receives a ResilientTimeoutException immediately") — not
 *       a Retry/Bulkhead/RateLimiter exception masking the timeout. The inner pattern's own
 *       resolution is only observable afterwards, best-effort, via {@link
 *       TimeoutEvent.AbandonedWorkerFailed}.</li>
 * </ol>
 */
class PolicyInterruptPropagationTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void should_interruptRetryBackoffAndSurfaceTimeoutToCaller_when_timeoutWrapsRetry() {
        var retryInterrupted = new CountDownLatch(1);
        var abandonedWorkerFailed = new CountDownLatch(1);
        var abandonedCause = new AtomicReference<Throwable>();

        var retry = Retry.<String>create("retry-under-test")
                .withMaxAttempts(3)
                .withInitialDelay(2_000)
                .withShouldRetry(e -> true)
                .withListener(event -> {
                    if (event instanceof RetryEvent.Interrupted) {
                        retryInterrupted.countDown();
                    }
                });
        var timeout = Timeout.<String>of("timeout-under-test", SHORT_TIMEOUT)
                .withListener(event -> {
                    if (event instanceof TimeoutEvent.AbandonedWorkerFailed abandoned) {
                        abandonedCause.set(abandoned.cause());
                        abandonedWorkerFailed.countDown();
                    }
                });
        var policy = Policy.compose(timeout).and(retry);

        // The operation always fails fast with a retryable failure (shouldRetry always true),
        // so Retry immediately enters a 2s backoff sleep on Timeout's worker thread, well past
        // Timeout's 100ms deadline.
        var outcome = policy.outcome(() -> {
            throw new RuntimeException("always fails");
        });

        assertThat(outcome).isInstanceOfSatisfying(Outcome.TimedOut.class,
                timedOut -> assertThat(timedOut.timeout()).isEqualTo(SHORT_TIMEOUT));

        var exception = assertThrows(ResilientTimeoutException.class, () -> policy.call(() -> {
            throw new RuntimeException("always fails");
        }));
        assertThat(exception.timeout()).isEqualTo(SHORT_TIMEOUT);

        assertThat(awaitLatch(retryInterrupted))
                .as("Retry must observe the interrupt during its backoff wait, not miss it")
                .isTrue();
        assertThat(awaitLatch(abandonedWorkerFailed))
                .as("the abandoned worker running Retry must report how the interrupt resolved")
                .isTrue();
        assertThat(abandonedCause.get())
                .as("the interrupt must resolve as an interruption, never as exhaustion or rejection")
                .isInstanceOf(RetryInterruptedException.class);
    }

    @Test
    void should_interruptBulkheadPermitWaitAndSurfaceTimeoutToCaller_when_timeoutWrapsBulkhead() throws InterruptedException {
        var permitHeld = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var abandonedWorkerFailed = new CountDownLatch(1);
        var abandonedCause = new AtomicReference<Throwable>();
        var innerOperationRan = new AtomicBoolean(false);

        var bulkhead = Bulkhead.<String>of("policy-interrupt-propagation-test", 1)
                .withMaxWait(Duration.ofSeconds(5))
                .withListener(event -> {
                    if (event instanceof BulkheadEvent.Permitted) {
                        permitHeld.countDown();
                    }
                });

        // Hold the bulkhead's single permit on a separate thread for the whole test, so the
        // Policy-driven call below finds no permit available and must wait for one.
        var holder = Thread.ofVirtual().start(() -> bulkhead.call(() -> {
            awaitLatch(releaseHolder);
            return "held";
        }));
        try {
            assertThat(awaitLatch(permitHeld)).as("holder must acquire the sole permit first").isTrue();

            var timeout = Timeout.<String>of("timeout-under-test", SHORT_TIMEOUT)
                    .withListener(event -> {
                        if (event instanceof TimeoutEvent.AbandonedWorkerFailed abandoned) {
                            abandonedCause.set(abandoned.cause());
                            abandonedWorkerFailed.countDown();
                        }
                    });
            var policy = Policy.compose(timeout).and(bulkhead);

            var outcome = policy.outcome(() -> {
                innerOperationRan.set(true);
                return "never";
            });

            assertThat(outcome).isInstanceOfSatisfying(Outcome.TimedOut.class,
                    timedOut -> assertThat(timedOut.timeout()).isEqualTo(SHORT_TIMEOUT));

            var exception = assertThrows(ResilientTimeoutException.class, () -> policy.call(() -> {
                innerOperationRan.set(true);
                return "never";
            }));
            assertThat(exception.timeout()).isEqualTo(SHORT_TIMEOUT);

            assertThat(innerOperationRan.get())
                    .as("the operation must never run: it is the permit wait itself that gets interrupted")
                    .isFalse();

            assertThat(awaitLatch(abandonedWorkerFailed))
                    .as("the abandoned worker blocked on the bulkhead permit must report the interrupt")
                    .isTrue();
            assertThat(abandonedCause.get())
                    .as("Bulkhead has no dedicated interrupted-exception type; interruption while " +
                            "waiting for a permit is reported as a wrapped InterruptedException")
                    .isInstanceOf(ResilientException.class)
                    .hasCauseInstanceOf(InterruptedException.class)
                    .hasMessageContaining("permit");
        } finally {
            releaseHolder.countDown();
            holder.join(AWAIT_TIMEOUT.toMillis());
        }
    }

    @Test
    void should_interruptRateLimiterWindowWaitAndSurfaceTimeoutToCaller_when_timeoutWrapsRateLimiter() {
        var abandonedWorkerFailed = new CountDownLatch(1);
        var abandonedCause = new AtomicReference<Throwable>();
        var innerOperationRan = new AtomicBoolean(false);

        var rateLimiter = RateLimiter.<String>of("policy-interrupt-propagation-test", 1, Duration.ofSeconds(2))
                .withMaxWait(Duration.ofSeconds(5));

        // Consume the single permit for the current window up front, so the Policy-driven calls
        // below find none left and must wait for the next window to open.
        rateLimiter.call(() -> "priming call");

        var timeout = Timeout.<String>of("timeout-under-test", SHORT_TIMEOUT)
                .withListener(event -> {
                    if (event instanceof TimeoutEvent.AbandonedWorkerFailed abandoned) {
                        abandonedCause.set(abandoned.cause());
                        abandonedWorkerFailed.countDown();
                    }
                });
        var policy = Policy.compose(timeout).and(rateLimiter);

        var outcome = policy.outcome(() -> {
            innerOperationRan.set(true);
            return "never";
        });

        assertThat(outcome).isInstanceOfSatisfying(Outcome.TimedOut.class,
                timedOut -> assertThat(timedOut.timeout()).isEqualTo(SHORT_TIMEOUT));

        var exception = assertThrows(ResilientTimeoutException.class, () -> policy.call(() -> {
            innerOperationRan.set(true);
            return "never";
        }));
        assertThat(exception.timeout()).isEqualTo(SHORT_TIMEOUT);

        assertThat(innerOperationRan.get())
                .as("the operation must never run: it is the window wait itself that gets interrupted")
                .isFalse();

        assertThat(awaitLatch(abandonedWorkerFailed))
                .as("the abandoned worker blocked on the rate limiter window must report the interrupt")
                .isTrue();
        assertThat(abandonedCause.get())
                .as("RateLimiter has no dedicated interrupted-exception type; interruption while " +
                        "waiting for the next window is reported as a wrapped InterruptedException")
                .isInstanceOf(ResilientException.class)
                .hasCauseInstanceOf(InterruptedException.class)
                .hasMessageContaining("rate limiter");
    }

    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting latch", e);
        }
    }
}
