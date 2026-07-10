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
 * Verifies that windows stay aligned to the creation instant and permit accounting
 * is consistent: each call is counted in exactly one window, no permits are lost.
 *
 * A single actor makes LIMIT calls (expect all to be admitted in window 1), then
 * the clock is advanced and multiple actors race to acquire permits in window 2.
 * Exactly LIMIT more must be admitted. If window alignment is broken, we might see
 * permit loss or double-counting across the window boundary.
 */
@JCStressTest
@State
@Outcome(id = "3", expect = Expect.ACCEPTABLE, desc = "Exactly 3 permits admitted in window 2 after window 1 was exhausted.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Window alignment broken: fewer than 3 admits or permits leaked/doubled.")
public class WindowAlignmentTest {

    private static final int LIMIT = 3;
    private static final Duration PERIOD = Duration.ofSeconds(10);

    private final AtomicInteger window2AdmittedCount = new AtomicInteger(0);
    private final ManualClock clock = ManualClock.create();

    private final RateLimiter<String> limiter = RateLimiter.<String>of("stress-window-alignment", LIMIT, PERIOD)
            .withClock(clock)
            .withMaxWait(Duration.ZERO);

    /**
     * Fills the first window by making LIMIT calls sequentially.
     * These are all expected to be admitted. If window alignment is broken,
     * this setup might leak permits or cause subsequent window to have wrong count.
     */
    public WindowAlignmentTest() {
        for (int i = 0; i < LIMIT; i++) {
            try {
                limiter.call(() -> "ok");
            } catch (RateLimiterException e) {
                throw new AssertionError("Setup failed: could not fill window 1", e);
            }
        }
        // Now advance to window 2
        clock.advance(PERIOD);
    }

    private void attemptWindow2Acquire() {
        try {
            limiter.call(() -> "ok");
            window2AdmittedCount.incrementAndGet();
        } catch (RateLimiterException rejected) {
            // Expected: window 2 has been filled already by other actors
        }
    }

    /**
     * Three actors race to acquire permits in window 2.
     * Each window is independent; exactly 3 should be admitted.
     */
    @Actor
    public void actor1() {
        attemptWindow2Acquire();
    }

    @Actor
    public void actor2() {
        attemptWindow2Acquire();
    }

    @Actor
    public void actor3() {
        attemptWindow2Acquire();
    }

    /**
     * Captures the final state: {@code r.r1 = window2AdmittedCount} (expect exactly 3).
     * If windows are not aligned, or if the advance operation corrupts state, we'll
     * see fewer than 3 admissions (permits lost) or more (double-counting).
     */
    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = window2AdmittedCount.get();
    }
}
