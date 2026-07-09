package io.github.teceli.resiliencia.stress.circuitbreaker;

import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreaker;
import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreakerOpenException;
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
 * Verifies that test-call permits in HalfOpen state are strictly limited
 * even under concurrent contention.
 *
 * Three actors race to make trial calls while the circuit is HalfOpen with a
 * single permit available. Exactly one must be admitted; the other two must be
 * rejected with {@code CircuitBreakerOpenException}, regardless of which actor
 * wins the Open → HalfOpen transition.
 *
 * Trial calls fail intentionally to reopen the circuit, ensuring late-arriving
 * actors cannot slip through after the HalfOpen episode ends.
 */
@JCStressTest
@State
@Outcome(id = "1, 2", expect = Expect.ACCEPTABLE, desc = "Exactly one trial call admitted, the other two rejected.")
@Outcome(expect = Expect.FORBIDDEN, desc = "More than one trial call admitted, or a call was neither admitted nor rejected.")
public class HalfOpenPermitLimitTest {

    private static final Duration WAIT_DURATION = Duration.ofSeconds(30);

    private final AtomicInteger admittedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);
    private final ManualClock clock = ManualClock.create();

    private final CircuitBreaker<String> breaker = CircuitBreaker.<String>of("stress-halfopen-permit-limit")
        .withSlidingWindowSize(1)
        .withFailureRateThreshold(0.5)
        .withWaitDurationInOpenState(WAIT_DURATION)
        .withPermittedCallsInHalfOpenState(1)
        .withClock(clock);

    public HalfOpenPermitLimitTest() {
        // Opens the circuit (window size 1) and fast-forwards past the wait duration, so the
        // actors below race on the Open -> HalfOpen transition and its single permit together.
        breaker.outcome(() -> {
            throw new IllegalStateException("stress-induced failure");
        });
        clock.advance(WAIT_DURATION);
    }

    private void attemptTrialCall() {
        try {
            breaker.call(() -> {
                throw new IllegalStateException("stress-induced trial-call failure");
            });
        } catch (CircuitBreakerOpenException rejected) {
            rejectedCount.incrementAndGet();
            return;
        } catch (IllegalStateException admittedFailure) {
            // Expected: an admitted trial call always fails on purpose, see class javadoc.
        }
        admittedCount.incrementAndGet();
    }

    @Actor
    public void actor1() {
        attemptTrialCall();
    }

    @Actor
    public void actor2() {
        attemptTrialCall();
    }

    @Actor
    public void actor3() {
        attemptTrialCall();
    }

    @Arbiter
    public void arbiter(II_Result r) {
        r.r1 = admittedCount.get();
        r.r2 = rejectedCount.get();
    }
}
