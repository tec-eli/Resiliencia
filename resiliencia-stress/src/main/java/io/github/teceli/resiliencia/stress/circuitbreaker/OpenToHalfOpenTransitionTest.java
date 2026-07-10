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
 * Verifies that the transition from Open to HalfOpen occurs exactly once
 * when multiple actors attempt it concurrently after the wait duration elapses.
 *
 * The wait duration is single-threaded setup via {@link ManualClock}, so all
 * actors race on the same state transition using a compare-and-swap. Only one
 * may win, so exactly one {@code HalfOpened} event must be emitted regardless
 * of actor interleaving.
 */
@JCStressTest
@State
@Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "Circuit half-opened exactly once.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Circuit half-opened zero or more than once.")
public class OpenToHalfOpenTransitionTest {

    private static final Duration WAIT_DURATION = Duration.ofSeconds(30);

    private final AtomicInteger halfOpenedCount = new AtomicInteger(0);
    private final ManualClock clock = ManualClock.create();

    private final CircuitBreaker<String> breaker = CircuitBreaker.<String>of("stress-open-to-halfopen")
        .withSlidingWindowSize(1)
        .withFailureRateThreshold(0.5)
        .withWaitDurationInOpenState(WAIT_DURATION)
        .withClock(clock)
        .withListener(event -> {
            if (event instanceof CircuitBreakerEvent.HalfOpened) {
                halfOpenedCount.incrementAndGet();
            }
        });

    /**
     * Opens the circuit with a single failing call (window size 1), then fast-forwards the
     * manual clock past the wait duration so both actors race on the same Open → HalfOpen
     * transition using compare-and-swap. Only one may win, ensuring exactly one HalfOpened
     * event is emitted regardless of actor interleaving.
     */
    public OpenToHalfOpenTransitionTest() {
        breaker.outcome(() -> {
            throw new IllegalStateException("stress-induced failure");
        });
        clock.advance(WAIT_DURATION);
    }

    @Actor
    public void actor1() {
        breaker.outcome(() -> "ok");
    }

    @Actor
    public void actor2() {
        breaker.outcome(() -> "ok");
    }

    /**
     * Captures the final state: {@code r.r1 = halfOpenedCount} (expect 1: transition should
     * occur exactly once via compare-and-swap, ensuring atomicity under concurrent access).
     */
    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = halfOpenedCount.get();
    }
}
