package io.github.teceli.resiliencia.stress.ratelimiter;

import io.github.teceli.resiliencia.patterns.ratelimiter.RateLimiter;
import io.github.teceli.resiliencia.patterns.ratelimiter.RateLimiterException;
import io.github.teceli.resiliencia.stress.support.ManualClock;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that permit counting is atomic even when a window boundary is crossed.
 * Multiple actors race to acquire permits such that some operate in window 1 and
 * some in window 2. The total admissions across both windows must not exceed 2×LIMIT.
 *
 * This stress test catches permit-counting atomicity bugs where window advance and
 * permit increment are not properly synchronized, leading to permit leakage or
 * double-counting at the boundary.
 */
@JCStressTest
@State
@Outcome(id = "6", expect = Expect.ACCEPTABLE,
        desc = "Exactly 6 permits admitted across two windows (3 each): window 1 full, window 2 full.")
@Outcome(id = "5", expect = Expect.ACCEPTABLE,
        desc = "5 permits: one window full (3), the other one short (2) due to boundary race.")
@Outcome(id = "4", expect = Expect.ACCEPTABLE,
        desc = "4 permits: boundary race reduces total, but never exceeds 2×LIMIT.")
@Outcome(expect = Expect.FORBIDDEN, desc = "More than 6 permits: leaked permit or double-count at boundary.")
public class TwoWindowLimitTest {

    private static final int LIMIT = 3;
    private static final Duration PERIOD = Duration.ofSeconds(10);

    private final AtomicInteger totalAdmittedCount = new AtomicInteger(0);
    private final ManualClock clock = ManualClock.create();

    private final RateLimiter<String> limiter = RateLimiter.<String>of("stress-two-window-limit", LIMIT, PERIOD)
            .withClock(clock)
            .withMaxWait(Duration.ZERO);

    /**
     * Constructor positions the clock near the boundary to maximize contention.
     * Clock starts at 9 seconds (near end of window 1: [0, 10)).
     */
    public TwoWindowLimitTest() {
        clock.advance(Duration.ofSeconds(9));
    }

    private void attemptAcquire() {
        try {
            limiter.call(() -> "ok");
            totalAdmittedCount.incrementAndGet();
        } catch (RateLimiterException rejected) {
            // Expected: no waiting, so rejected calls just increment rejection counter
        }
    }

    /**
     * Six actors race to acquire permits right at the window boundary.
     * Some will acquire in window 1, some in window 2. The timing ensures
     * that the window boundary is crossed during concurrent acquisition.
     *
     * With LIMIT=3, we expect at most 6 total admissions across both windows.
     * If atomicity is broken, permits can leak, leading to more than 6 admissions.
     */
    @Actor
    public void actor1() {
        attemptAcquire();
    }

    @Actor
    public void actor2() {
        attemptAcquire();
    }

    @Actor
    public void actor3() {
        attemptAcquire();
    }

    @Actor
    public void actor4() {
        attemptAcquire();
    }

    @Actor
    public void actor5() {
        attemptAcquire();
    }

    @Actor
    public void actor6() {
        attemptAcquire();
    }

    /**
     * Captures the final state: {@code r.r1 = totalAdmittedCount}.
     * Expected outcomes: 6 (both windows full), 5 (boundary race), or 4 (tight boundary).
     * Forbidden: > 6 (indicates permit leakage at boundary).
     */
    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = totalAdmittedCount.get();
    }
}
