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
 * Setup: two windows, each with limit 3. A single actor advances the clock by exactly
 * one {@code PERIOD}, so the window 1 → window 2 transition itself is deterministic;
 * the other five actors only call {@code attemptAcquire()}, so which side of that one
 * transition each of them lands on is still genuinely racy. Every attempt is either
 * admitted or rejected (never lost), and neither window can admit more than its limit,
 * so total admissions across the run must be one of 3, 4, 5, or 6 with admitted +
 * rejected always equal to 6.
 */
@JCStressTest
@State
@Outcome(id = "6, 0", expect = Expect.ACCEPTABLE,
        desc = "Both windows filled (3 each): all non-advancing actors landed 3-and-3 across the boundary.")
@Outcome(id = "5, 1", expect = Expect.ACCEPTABLE,
        desc = "5 admitted: one window got only 1-2 attempts, so it couldn't fill to 3.")
@Outcome(id = "4, 2", expect = Expect.ACCEPTABLE,
        desc = "4 admitted: one window got only 1 attempt, so it couldn't fill to 3.")
@Outcome(id = "3, 3", expect = Expect.ACCEPTABLE,
        desc = "3 admitted: the clock advance happened before any other actor read the clock, so all five "
                + "non-advancing actors landed in the same window as the advancing actor.")
@Outcome(expect = Expect.FORBIDDEN,
        desc = "admitted + rejected != 6, or more than 6 admitted: a permit was lost or double-counted.")
public class WindowBoundaryTest {

    private static final int LIMIT = 3;
    private static final Duration PERIOD = Duration.ofSeconds(10);

    private final AtomicInteger admittedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);
    private final ManualClock clock = ManualClock.create();

    private final RateLimiter<String> limiter = RateLimiter.<String>of("stress-window-boundary", LIMIT, PERIOD)
            .withClock(clock)
            .withMaxWait(Duration.ZERO); // No waiting, fail fast to simplify assertions

    private void attemptAcquire() {
        try {
            limiter.call(() -> "ok");
            admittedCount.incrementAndGet();
        } catch (RateLimiterException rejected) {
            rejectedCount.incrementAndGet();
        }
    }

    /**
     * Actors 1-3 only attempt to acquire; each may land in window 1 or window 2
     * depending on how it's scheduled relative to actor4's clock advance.
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
     * The single actor that advances the clock, by exactly one {@code PERIOD}. This is
     * the only clock mutation in the run, so the window 1 → window 2 transition itself is
     * deterministic in magnitude; only its timing relative to the other actors is racy.
     * This actor's own attempt always lands in window 2, since the advance happens-before
     * its attemptAcquire() in program order.
     */
    @Actor
    public void actor4() {
        clock.advance(PERIOD);
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
     * Captures the final state: {@code r.r1 = admittedCount}, {@code r.r2 = rejectedCount}.
     * The two must always sum to 6 (every attempt is admitted or rejected, never lost), and
     * admittedCount must land in {3, 4, 5, 6} depending on how the six attempts split across
     * the boundary. Anything else indicates permit loss or double-counting.
     */
    @Arbiter
    public void arbiter(II_Result r) {
        r.r1 = admittedCount.get();
        r.r2 = rejectedCount.get();
    }
}
