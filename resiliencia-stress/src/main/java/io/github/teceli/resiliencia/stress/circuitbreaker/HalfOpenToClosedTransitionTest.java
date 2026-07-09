package io.github.teceli.resiliencia.stress.circuitbreaker;

import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreaker;
import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreakerEvent;
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
 * Verifies that the transition from HalfOpen to Closed occurs exactly once
 * when multiple actors simultaneously deliver the final successful call needed.
 *
 * The circuit is pre-positioned in HalfOpen state with one successful call
 * already recorded (single-threaded setup), leaving one final permit needed
 * to close. Two actors race to deliver that last success via compare-and-swap.
 * Exactly one {@code Closed} event must be emitted regardless of interleaving.
 */
@JCStressTest
@State
@Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "Circuit closed exactly once.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Circuit closed zero or more than once.")
public class HalfOpenToClosedTransitionTest {

    private static final Duration WAIT_DURATION = Duration.ofSeconds(30);
    private static final int PERMITTED_CALLS_IN_HALF_OPEN_STATE = 3;

    private final AtomicInteger closedCount = new AtomicInteger(0);
    private final ManualClock clock = ManualClock.create();

    private final CircuitBreaker<String> breaker = CircuitBreaker.<String>of("stress-halfopen-to-closed")
        .withSlidingWindowSize(1)
        .withFailureRateThreshold(0.5)
        .withWaitDurationInOpenState(WAIT_DURATION)
        .withPermittedCallsInHalfOpenState(PERMITTED_CALLS_IN_HALF_OPEN_STATE)
        .withClock(clock)
        .withListener(event -> {
            if (event instanceof CircuitBreakerEvent.Closed) {
                closedCount.incrementAndGet();
            }
        });

    public HalfOpenToClosedTransitionTest() {
        // Opens the circuit (window size 1), fast-forwards past the wait duration, then makes
        // one successful call single-threaded: this both flips Open -> HalfOpen and consumes the
        // first of the three permitted test calls, leaving exactly two for the actors to race on.
        breaker.outcome(() -> {
            throw new IllegalStateException("stress-induced failure");
        });
        clock.advance(WAIT_DURATION);
        breaker.outcome(() -> "ok");
    }

    @Actor
    public void actor1() {
        breaker.outcome(() -> "ok");
    }

    @Actor
    public void actor2() {
        breaker.outcome(() -> "ok");
    }

    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = closedCount.get();
    }
}
