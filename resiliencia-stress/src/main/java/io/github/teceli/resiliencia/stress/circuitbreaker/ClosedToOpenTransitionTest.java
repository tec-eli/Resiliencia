package io.github.teceli.resiliencia.stress.circuitbreaker;

import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreaker;
import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreakerEvent;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that the transition from Closed to Open state occurs exactly once
 * when multiple actors concurrently attempt to trigger the transition.
 *
 * The sliding window is primed to be full and past the failure rate threshold.
 * Two actors simultaneously record failing calls. A compare-and-swap mechanism
 * ensures only one may win the state transition, so exactly one {@code Opened}
 * event must be emitted regardless of actor interleaving.
 */
@JCStressTest
@State
@Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "Circuit opened exactly once.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Circuit opened zero or more than once.")
public class ClosedToOpenTransitionTest {

    private static final int SLIDING_WINDOW_SIZE = 2;
    private static final double FAILURE_RATE_THRESHOLD = 0.5;

    private final AtomicInteger openedCount = new AtomicInteger(0);

    private final CircuitBreaker<Void> breaker = CircuitBreaker.<Void>of("stress-closed-to-open")
        .withSlidingWindowSize(SLIDING_WINDOW_SIZE)
        .withFailureRateThreshold(FAILURE_RATE_THRESHOLD)
        .withListener(event -> {
            if (event instanceof CircuitBreakerEvent.Opened) {
                openedCount.incrementAndGet();
            }
        });

    /**
     * Seeds the window with one failure so either actor's own failing call is enough to make
     * the window full and past threshold, without the seed call itself racing the actors.
     */
    public ClosedToOpenTransitionTest() {
        recordFailure();
    }

    /** Emits a single failure to seed the sliding window for the race condition. */
    private void recordFailure() {
        breaker.outcome(() -> {
            throw new IllegalStateException("stress-induced failure");
        });
    }

    @Actor
    public void actor1() {
        recordFailure();
    }

    @Actor
    public void actor2() {
        recordFailure();
    }

    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = openedCount.get();
    }
}
