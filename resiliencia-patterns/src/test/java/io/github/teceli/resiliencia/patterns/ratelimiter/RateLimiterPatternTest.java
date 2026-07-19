package io.github.teceli.resiliencia.patterns.ratelimiter;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.spi.Clock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the RateLimiter pattern, driven by a manual clock so window and wait
 * behavior is deterministic and instant.
 */
class RateLimiterPatternTest {

    private static final Duration PERIOD = Duration.ofMillis(100);

    @Test
    void should_permitCalls_when_underLimitWithinWindow() {
        var limiter = RateLimiter.<String>of("rate-limiter",2, PERIOD).withClock(new ManualClock());

        assertThat(limiter.call(() -> "first")).isEqualTo("first");
        assertThat(limiter.call(() -> "second")).isEqualTo("second");
    }

    @Test
    void should_throwRateLimiterException_when_limitExceededWithinWindow() {
        var limiter = RateLimiter.<String>of("rate-limiter",1, PERIOD).withClock(new ManualClock());
        limiter.call(() -> "first");

        var exception = assertThrows(RateLimiterException.class, () ->
            limiter.call(() -> "rejected"));
        assertThat(exception.limit()).isEqualTo(1);
        assertThat(exception.period()).isEqualTo(PERIOD);
        assertThat(exception.maxWait()).isEqualTo(Duration.ZERO);
    }

    @Test
    void should_returnFailureWithRateLimiterException_when_usingOutcomeMethod() {
        var limiter = RateLimiter.<String>of("rate-limiter",1, PERIOD).withClock(new ManualClock());
        limiter.call(() -> "first");

        var outcome = limiter.outcome(() -> "rejected");

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Failure.class, f ->
                        assertThat(f.cause()).isInstanceOf(RateLimiterException.class));
    }

    @Test
    void should_permitCallsAgain_when_nextWindowOpens() {
        var manualClock = new ManualClock();
        var limiter = RateLimiter.<String>of("rate-limiter",1, PERIOD).withClock(manualClock);
        limiter.call(() -> "first");

        manualClock.advance(PERIOD);

        assertThat(limiter.call(() -> "second window")).isEqualTo("second window");
    }

    @Test
    void should_alignWindows_when_limiterWasIdleForManyPeriods() {
        var manualClock = new ManualClock();
        var limiter = RateLimiter.<String>of("rate-limiter",1, PERIOD).withClock(manualClock);
        limiter.call(() -> "first");

        // Land mid-window many periods later: one permit is available, the next is not.
        manualClock.advance(PERIOD.multipliedBy(1000).plus(PERIOD.dividedBy(2)));

        assertThat(limiter.call(() -> "after idle")).isEqualTo("after idle");
        assertThrows(RateLimiterException.class, () ->
            limiter.call(() -> "rejected"));
    }

    @Test
    void should_notThrowArithmeticException_when_periodIsTinyAndIdleForCenturies() {
        var manualClock = new ManualClock();
        var limiter = RateLimiter.<String>of("rate-limiter", 1, Duration.ofNanos(1)).withClock(manualClock);
        limiter.call(() -> "first");

        // periodsElapsed (elapsed / period) overflows long here, which used to escape
        // advanceWindow as an ArithmeticException instead of being handled.
        manualClock.advance(Duration.ofDays(365 * 300));

        assertThat(limiter.call(() -> "after centuries idle")).isEqualTo("after centuries idle");
    }

    @Test
    void should_notThrowDateTimeException_when_clockIsNearInstantMaxAfterIdlePeriod() {
        var period = Duration.ofNanos(100);
        var manualClock = new ManualClock(Instant.MAX.minus(period.multipliedBy(2)));
        var limiter = RateLimiter.<String>of("rate-limiter", 1, period).withClock(manualClock);
        limiter.call(() -> "first");

        manualClock.advance(period.multipliedBy(2));

        assertThat(limiter.call(() -> "near instant max"))
                .as("a permit near Instant.MAX should still be granted, not throw DateTimeException")
                .isEqualTo("near instant max");

        var outcome = limiter.outcome(() -> "rejected in same window");
        assertThat(outcome)
                .as("outcome() never throws, even when the next window's start overflows Instant")
                .isInstanceOfSatisfying(Outcome.Failure.class, f ->
                        assertThat(f.cause()).isInstanceOf(RateLimiterException.class));
    }

    @Test
    void should_waitForNextWindow_when_maxWaitAllowsIt() {
        var manualClock = new ManualClock();
        var limiter = RateLimiter.<String>of("rate-limiter",1, PERIOD)
                .withMaxWait(PERIOD.multipliedBy(2))
                .withClock(manualClock);
        limiter.call(() -> "first");
        var before = manualClock.instant();

        assertThat(limiter.call(() -> "waited")).isEqualTo("waited");

        assertThat(Duration.between(before, manualClock.instant()))
                .as("the excess call should have slept into the next window")
                .isEqualTo(PERIOD);
    }

    @Test
    void should_rejectWithoutWaiting_when_maxWaitEndsBeforeNextWindow() {
        var manualClock = new ManualClock();
        var limiter = RateLimiter.<String>of("rate-limiter",1, PERIOD)
                .withMaxWait(Duration.ofMillis(30))
                .withClock(manualClock);
        limiter.call(() -> "first");
        var before = manualClock.instant();

        assertThrows(RateLimiterException.class, () ->
            limiter.call(() -> "rejected"));

        assertThat(manualClock.instant())
                .as("a hopeless wait should be rejected immediately, not slept out")
                .isEqualTo(before);
    }

    @Test
    void should_rethrowOriginalException_when_operationFails() {
        var limiter = RateLimiter.<String>of("rate-limiter",1, PERIOD).withClock(new ManualClock());
        var boom = new IllegalStateException("boom");

        var exception = assertThrows(IllegalStateException.class, () ->
            limiter.call(() -> {
                throw boom;
            }));
        assertThat(exception).isSameAs(boom);
    }

    @Test
    void should_notRefundPermit_when_operationFails() {
        var limiter = RateLimiter.<String>of("rate-limiter",1, PERIOD).withClock(new ManualClock());

        assertThrows(IllegalStateException.class, () ->
            limiter.call(() -> {
                throw new IllegalStateException("boom");
            }));

        assertThrows(RateLimiterException.class, () ->
            limiter.call(() -> "rejected"));
    }

    @Test
    void should_emitPermittedAndRejectedEvents() {
        var events = new ArrayList<RateLimiterEvent>();
        var limiter = RateLimiter.<String>of("rate-limiter",1, PERIOD)
                .withClock(new ManualClock())
                .withListener(event -> events.add((RateLimiterEvent) event));

        limiter.call(() -> "first");
        limiter.outcome(() -> "rejected");

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOfSatisfying(RateLimiterEvent.Permitted.class,
                p -> assertThat(p.remainingPermits()).isZero());
        assertThat(events.get(1))
                .isInstanceOfSatisfying(RateLimiterEvent.Rejected.class, r ->
                        assertThat(r.estimatedWait()).isEqualTo(PERIOD));
    }

    @Test
    void should_reportRemainingPermits_when_multiplePermitsAvailable() {
        var events = new ArrayList<RateLimiterEvent>();
        var limiter = RateLimiter.<String>of("rate-limiter",3, PERIOD)
                .withClock(new ManualClock())
                .withListener(event -> events.add((RateLimiterEvent) event));

        limiter.call(() -> "first");
        limiter.call(() -> "second");

        var permitted = events.stream()
                .filter(RateLimiterEvent.Permitted.class::isInstance)
                .map(RateLimiterEvent.Permitted.class::cast)
                .toList();
        assertThat(permitted).hasSize(2);
        assertThat(permitted.get(0).remainingPermits()).isEqualTo(2);
        assertThat(permitted.get(1).remainingPermits()).isEqualTo(1);
    }

    @Test
    void should_returnOperationResult_when_listenerThrowsException() {
        var limiter = RateLimiter.<String>of("rate-limiter",1, PERIOD)
                .withClock(new ManualClock())
                .withListener(event -> {
                    throw new IllegalStateException("listener boom");
                });

        assertThat(limiter.call(() -> "done")).isEqualTo("done");
    }

    @Test
    void should_notOverflow_when_maxWaitExceedsMaxMillisDuration() {
        // Instant.plus() would overflow for a duration this large; RateLimiter must clamp it
        // to MAX_MILLIS_DURATION instead of letting ArithmeticException/DateTimeException escape.
        var manualClock = new ManualClock();
        var limiter = RateLimiter.<String>of("rate-limiter", 1, PERIOD)
                .withMaxWait(Duration.ofMillis(Long.MAX_VALUE).plusDays(1))
                .withClock(manualClock);
        limiter.call(() -> "first");

        assertThat(limiter.call(() -> "waited")).isEqualTo("waited");
    }

    @Test
    void should_backOffThenYield_when_casRetriesExceedSpinThreshold() throws InterruptedException {
        // Exercises the CAS-retry back-off path in tryAcquire (Thread.onSpinWait() below the
        // threshold, Thread.yield() above it) under enough contention that some threads are
        // guaranteed to exceed CAS_SPIN_RETRY_THRESHOLD before succeeding. This does not assert
        // internal counters directly (private), only that every permit is still granted exactly
        // once despite it, i.e. the back-off path itself never loses or double-grants a permit.
        var threadCount = 64;
        var limiter = RateLimiter.<Integer>of("rate-limiter", threadCount, Duration.ofSeconds(30));
        var ready = new CountDownLatch(threadCount);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threadCount);
        var granted = new AtomicInteger();

        for (var i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                ready.countDown();
                awaitQuietly(start);
                limiter.call(() -> 1);
                granted.incrementAndGet();
                done.countDown();
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(granted.get()).isEqualTo(threadCount);
    }

    @Test
    void should_throwNullPointerException_when_listenerIsNull() {
        assertThatNullPointerException()
            .isThrownBy(() -> RateLimiter.<String>of("rate-limiter", 1, PERIOD).withListener(null));
    }

    @Test
    void should_reportRateLimiterKind_when_patternKindQueried() {
        var limiter = RateLimiter.<String>of("rate-limiter",1, PERIOD);

        assertThat(limiter.patternKind()).isEqualTo(PatternKind.RATE_LIMITER);
        assertThat(limiter.patternName()).isEqualTo("rate-limiter");
    }

    @Test
    void should_rejectInvalidConfiguration_when_constructed() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            RateLimiter.<String>of("rate-limiter",0, PERIOD));
        assertThatIllegalArgumentException().isThrownBy(() ->
            RateLimiter.<String>of("rate-limiter",1, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() ->
            RateLimiter.<String>of("rate-limiter",1, PERIOD).withMaxWait(Duration.ofMillis(-1)));
        assertThatNullPointerException()
            .isThrownBy(() ->
                RateLimiter.<String>of("rate-limiter",1, null));
    }

    @Test
    void should_createIndependentInstanceWithFreshWindow_when_witherCalled() {
        var manualClock = new ManualClock();
        var original = RateLimiter.<String>of("rate-limiter",1, PERIOD).withClock(manualClock);
        original.call(() -> "uses original's only permit");

        var reconfigured = original.withLimit(1);

        assertThat(reconfigured.call(() -> "independent")).isEqualTo("independent");
    }

    @Test
    void should_grantExactlyLimitPermits_when_manyThreadsContendConcurrently() throws InterruptedException {
        var threadCount = 500;
        var limit = 100;
        var limiter = RateLimiter.<Integer>of("rate-limiter", limit, Duration.ofSeconds(30));
        var ready = new CountDownLatch(threadCount);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threadCount);
        var granted = new AtomicInteger();
        var rejected = new AtomicInteger();
        var threads = new ArrayList<Thread>();

        for (var i = 0; i < threadCount; i++) {
            var thread = Thread.ofVirtual().unstarted(() -> {
                ready.countDown();
                try {
                    start.await();
                    limiter.call(() -> 1);
                    granted.incrementAndGet();
                } catch (RateLimiterException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            threads.add(thread);
        }

        threads.forEach(Thread::start);
        ready.await();
        start.countDown();
        done.await();

        assertThat(granted.get())
                .as("exactly the configured limit should be granted, even under heavy CAS contention")
                .isEqualTo(limit);
        assertThat(rejected.get()).isEqualTo(threadCount - limit);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted", e);
        }
    }

    /**
     * Deterministic clock for tests: time only moves when advanced explicitly or when the
     * limiter sleeps, so waits complete instantly.
     */
    private static final class ManualClock implements Clock {
        private Instant now;

        ManualClock() {
            this(Instant.parse("2026-01-01T00:00:00Z"));
        }

        ManualClock(Instant now) {
            this.now = now;
        }

        @Override
        public synchronized Instant instant() {
            return now;
        }

        @Override
        public synchronized void sleep(long millis) {
            now = now.plusMillis(millis);
        }

        synchronized void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
