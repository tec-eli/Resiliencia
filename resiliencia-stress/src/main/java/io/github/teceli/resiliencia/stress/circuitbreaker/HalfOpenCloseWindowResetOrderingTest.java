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
 * Verifies issue #67: {@code CircuitBreaker.close()} must not let a losing thread's spurious
 * {@code window.reset()} silently wipe an observation recorded into the freshly-Closed window.
 *
 * The circuit is pre-positioned in HalfOpen with one successful call already recorded
 * (single-threaded setup), leaving three permits. Two actors race to deliver the final
 * successful test calls that close the circuit, while a third actor concurrently delivers a
 * failing call. Regardless of interleaving, that failing call must never be silently absorbed:
 * either it lands before the close commits (reopening the circuit directly) or it lands against
 * the fresh Closed window (size 1, threshold 0.5), which must still reopen the circuit. A Closed
 * circuit with zero reopen events despite the guaranteed failure is forbidden.
 */
@JCStressTest
@State
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Closed once, then reopened by the failing call.")
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE,
        desc = "The failing call opened the circuit before the racers' close committed.")
@Outcome(id = "1, 0", expect = Expect.FORBIDDEN,
        desc = "Circuit closed but the guaranteed failure was silently lost (window corruption).")
public class HalfOpenCloseWindowResetOrderingTest {

    private static final Duration WAIT_DURATION = Duration.ofSeconds(30);
    private static final int PERMITTED_CALLS_IN_HALF_OPEN_STATE = 4;

    private final AtomicInteger closedCount = new AtomicInteger(0);
    private final AtomicInteger openedCount = new AtomicInteger(0);
    private final ManualClock clock = ManualClock.create();

    private final CircuitBreaker<String> breaker = CircuitBreaker.<String>of("stress-close-window-reset-ordering")
        .withSlidingWindowSize(1)
        .withFailureRateThreshold(0.5)
        .withWaitDurationInOpenState(WAIT_DURATION)
        .withPermittedCallsInHalfOpenState(PERMITTED_CALLS_IN_HALF_OPEN_STATE)
        .withClock(clock)
        .withListener(event -> {
            if (event instanceof CircuitBreakerEvent.Closed) {
                closedCount.incrementAndGet();
            } else if (event instanceof CircuitBreakerEvent.Opened) {
                openedCount.incrementAndGet();
            }
        });

    /**
     * Opens the circuit (window size 1), fast-forwards past the wait duration, then makes one
     * successful call single-threaded: this both flips Open → HalfOpen and consumes the first
     * of the four permitted test calls, leaving three for the actors to race on.
     */
    public HalfOpenCloseWindowResetOrderingTest() {
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
        breaker.outcome(() -> {
            throw new IllegalStateException("stress-induced failure");
        });
    }

    /**
     * Captures the final state: {@code r.r1 = closedCount}, {@code r.r2 = openedCount}. The only
     * forbidden combination is (1, 0): a Closed circuit despite actor3's guaranteed failure never
     * having reopened it.
     */
    @Arbiter
    public void arbiter(II_Result r) {
        r.r1 = closedCount.get();
        r.r2 = openedCount.get();
    }
}
