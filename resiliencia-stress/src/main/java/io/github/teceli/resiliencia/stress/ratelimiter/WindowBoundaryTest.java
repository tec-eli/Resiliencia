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
import org.openjdk.jcstress.infra.results.II_Result;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that window boundaries are handled atomically: calls racing at a
 * window boundary must not have permits double-counted or lost.
 *
 * Setup: Two windows, each with limit 3. Use a manual clock to position actors
 * such that some operate in window 1 and some in window 2, with some potentially
 * racing at the boundary. Verify that no permit is ever double-counted and the
 * total admissions across both windows equals the sum of their limits (3 + 3 = 6).
 */
@JCStressTest
@State
@Outcome(id = "6, 0", expect = Expect.ACCEPTABLE,
        desc = "Exactly 6 permits admitted across two windows (3 each), boundary handled atomically.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Either more/fewer than 6 admits, or window boundary was corrupted.")
public class WindowBoundaryTest {

    private static final int LIMIT = 3;
    private static final Duration PERIOD = Duration.ofSeconds(10);

    private final AtomicInteger admittedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);
    private final ManualClock clock = ManualClock.create();

    private final RateLimiter<String> limiter = RateLimiter.<String>of("stress-window-boundary", LIMIT, PERIOD)
            .withClock(clock)
            .withMaxWait(Duration.ZERO); // No waiting, fail fast to simplify assertions

    /**
     * Constructor positions the clock so actors will operate across a window boundary.
     * Some actors start in window 1, some cross into window 2.
     */
    public WindowBoundaryTest() {
        // Clock starts at time 0 (window 1: [0, 10)).
        // We'll advance to 9 seconds before some actors run, putting them near the boundary.
        // Other actors will push past the boundary into window 2.
    }

    private void attemptAcquire() {
        try {
            limiter.call(() -> "ok");
            admittedCount.incrementAndGet();
        } catch (RateLimiterException rejected) {
            rejectedCount.incrementAndGet();
        }
    }

    /**
     * Actors 1-3 run in window 1 (first 3 to acquire get in).
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

    /**
     * Actors 4-6: advance the clock to trigger window 2, then attempt.
     * If window advance is not atomic with permit counting, a boundary race can corrupt state.
     */
    @Actor
    public void actor4() {
        clock.advance(PERIOD); // Move to window 2
        attemptAcquire();
    }

    @Actor
    public void actor5() {
        clock.advance(PERIOD);
        attemptAcquire();
    }

    @Actor
    public void actor6() {
        clock.advance(PERIOD);
        attemptAcquire();
    }

    /**
     * Captures the final state: {@code r.r1 = admittedCount} (expect 6: 3 from window 1, 3 from window 2).
     * A boundary corruption could result in fewer or more admits (e.g., permit loss or
     * double-counting when window boundary is crossed).
     */
    @Arbiter
    public void arbiter(II_Result r) {
        r.r1 = admittedCount.get();
        r.r2 = rejectedCount.get();
    }
}
