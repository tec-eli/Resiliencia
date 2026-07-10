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
 * Verifies that a RateLimiter never permits more than the configured limit
 * in a single window, even under concurrent contention.
 *
 * Multiple actors race to acquire permits from a single window with limit N.
 * Exactly N must be admitted; all others must fail with {@code RateLimiterException}.
 * Uses a manual clock to keep all actors in the same window and avoid time-dependent
 * races.
 */
@JCStressTest
@State
@Outcome(id = "5, 0", expect = Expect.ACCEPTABLE, desc = "Exactly 5 permits admitted in limit=5, zero rejected.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Either more than 5 admits or some unexplained outcome.")
public class PermitLimitTest {

    private static final int LIMIT = 5;
    private static final Duration PERIOD = Duration.ofSeconds(60);

    private final AtomicInteger admittedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);
    private final ManualClock clock = ManualClock.create();

    private final RateLimiter<String> limiter = RateLimiter.<String>of("stress-permit-limit", LIMIT, PERIOD)
            .withClock(clock);

    /**
     * Attempts to acquire a permit and counts admissions vs rejections.
     * All actors run in the same window because we never advance the clock.
     */
    private void attemptAcquire() {
        try {
            limiter.call(() -> "ok");
            admittedCount.incrementAndGet();
        } catch (RateLimiterException rejected) {
            rejectedCount.incrementAndGet();
        }
    }

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

    /**
     * Captures the final state: {@code r.r1 = admittedCount} (expect exactly 5).
     * If this is not 5, it means either more permits were admitted (violated constraint)
     * or fewer were admitted (lost permits). Either is a concurrency bug.
     */
    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = admittedCount.get();
    }
}
