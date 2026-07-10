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
import org.openjdk.jcstress.infra.results.II_Result;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that concurrent outcome recording during the HalfOpen→Closed transition
 * doesn't corrupt the sliding window (issue #43).
 *
 * The circuit is pre-positioned in HalfOpen with one successful call already recorded
 * (single-threaded setup), leaving three permits for the actors to race on. Multiple
 * actors concurrently record outcomes while one actor's final successful call closes
 * the circuit. The window reset during close() must not corrupt data being written
 * concurrently by other threads. Exactly one {@code Closed} event must be emitted,
 * and all outcomes must be recorded correctly despite the concurrent transition.
 */
@JCStressTest
@State
@Outcome(id = "1, 3", expect = Expect.ACCEPTABLE, desc = "Circuit closed exactly once, all three outcomes recorded.")
@Outcome(id = "1, 2", expect = Expect.ACCEPTABLE, desc = "Circuit closed exactly once, two outcomes recorded (timing variation).")
@Outcome(expect = Expect.FORBIDDEN, desc = "Circuit closed zero or more than once, or unexpected outcome count.")
public class ClosedAfterHalfOpenWithConcurrentOutcomeTest {

    private static final Duration WAIT_DURATION = Duration.ofSeconds(30);
    private static final int PERMITTED_CALLS_IN_HALF_OPEN_STATE = 4;

    private final AtomicInteger closedCount = new AtomicInteger(0);
    private final AtomicInteger recordedCount = new AtomicInteger(0);
    private final ManualClock clock = ManualClock.create();

    private final CircuitBreaker<String> breaker = CircuitBreaker.<String>of("stress-concurrent-outcome-during-close")
        .withSlidingWindowSize(3)
        .withFailureRateThreshold(0.5)
        .withWaitDurationInOpenState(WAIT_DURATION)
        .withPermittedCallsInHalfOpenState(PERMITTED_CALLS_IN_HALF_OPEN_STATE)
        .withClock(clock)
        .withListener(event -> {
            if (event instanceof CircuitBreakerEvent.Closed) {
                closedCount.incrementAndGet();
            } else if (event instanceof CircuitBreakerEvent.CallRecorded) {
                recordedCount.incrementAndGet();
            }
        });

    public ClosedAfterHalfOpenWithConcurrentOutcomeTest() {
        // Opens the circuit, fast-forwards past the wait duration, then makes one successful
        // call single-threaded: this transitions to HalfOpen and consumes one of the four
        // permitted calls, leaving three for the actors to race on.
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

    @Actor
    public void actor3() {
        breaker.outcome(() -> "ok");
    }

    @Arbiter
    public void arbiter(II_Result r) {
        r.r1 = closedCount.get();
        r.r2 = recordedCount.get();
    }
}
