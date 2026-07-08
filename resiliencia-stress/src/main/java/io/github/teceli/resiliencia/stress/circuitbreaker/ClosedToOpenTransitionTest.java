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
 * CircuitBreaker.open() (CircuitBreaker.java:385-389) CASes from Closed to Open. Two actors each
 * record a failing call once the sliding window is already primed to be full and past the failure
 * rate threshold; only one of them may win the CAS, so exactly one {@code Opened} event must ever
 * be emitted, regardless of actor interleaving.
 */
@JCStressTest
@State
@Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "Circuit opened exactly once.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Circuit opened zero or more than once.")
public class ClosedToOpenTransitionTest {

    private final AtomicInteger openedCount = new AtomicInteger(0);

    private final CircuitBreaker<Void> breaker = CircuitBreaker.<Void>of("stress-closed-to-open")
        .withSlidingWindowSize(2)
        .withFailureRateThreshold(0.5)
        .withListener(event -> {
            if (event instanceof CircuitBreakerEvent.Opened) {
                openedCount.incrementAndGet();
            }
        });

    public ClosedToOpenTransitionTest() {
        // Seeds the window with one failure so either actor's own failing call is enough to make
        // the window full and past threshold, without the seed call itself racing the actors.
        recordFailure();
    }

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
