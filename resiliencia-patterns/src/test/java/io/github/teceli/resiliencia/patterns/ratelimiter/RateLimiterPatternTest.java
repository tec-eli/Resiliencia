package io.github.teceli.resiliencia.patterns.ratelimiter;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.spi.Clock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
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
    void should_reportRateLimiterKind_when_patternKindQueried() {
        var limiter = RateLimiter.<String>of("rate-limiter",1, PERIOD);

        assertThat(limiter.patternKind()).isEqualTo(PatternKind.RATE_LIMITER);
        assertThat(limiter.patternName()).isEqualTo("rate-limiter");
    }

    @Test
    void should_rejectInvalidConfiguration_when_constructed() {
        assertThrows(IllegalArgumentException.class, () ->
            RateLimiter.<String>of("rate-limiter",0, PERIOD));
        assertThrows(IllegalArgumentException.class, () ->
            RateLimiter.<String>of("rate-limiter",1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
            RateLimiter.<String>of("rate-limiter",1, PERIOD).withMaxWait(Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class, () ->
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

    /**
     * Deterministic clock for tests: time only moves when advanced explicitly or when the
     * limiter sleeps, so waits complete instantly.
     */
    private static final class ManualClock implements Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

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
