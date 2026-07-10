package io.github.teceli.resiliencia.stress.circuitbreaker;

import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreaker;
import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreakerEvent;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that concurrent outcome recordings are atomic and consistent.
 *
 * One actor records a success, the other a failure, concurrently. Each recorded
 * call must emit exactly one {@code CallRecorded} event (no loss or duplication).
 * The sliding window must never be observed in a half-updated state—whichever
 * call is observed last must reflect the correct failure rate.
 */
@JCStressTest
@State
@Outcome(id = "2, 1", expect = Expect.ACCEPTABLE, desc = "Both calls recorded exactly once; final failure rate (0.5) was observed.")
@Outcome(id = "2, 2", expect = Expect.ACCEPTABLE, desc = "Both calls recorded exactly once; final failure rate (0.5) was observed by both readers.")
@Outcome(expect = Expect.FORBIDDEN, desc = "A call was lost/duplicated, or the final failure rate was never observed.")
public class ConcurrentOutcomeRecordingTest {

    private final AtomicInteger recordedCount = new AtomicInteger(0);
    private final AtomicInteger finalRateObservedCount = new AtomicInteger(0);

    private final CircuitBreaker<String> breaker = CircuitBreaker.<String>of("stress-outcome-recording")
        .withSlidingWindowSize(2)
        .withFailureRateThreshold(1.0)
        .withListener(event -> {
            if (event instanceof CircuitBreakerEvent.CallRecorded recorded) {
                recordedCount.incrementAndGet();
                if (recorded.currentFailureRate() == 0.5) {
                    finalRateObservedCount.incrementAndGet();
                }
            }
        });

    /**
     * Intentionally starts with an empty window to verify outcome recording atomicity from
     * ground state. Unlike state-transition tests that pre-position the circuit, this test
     * captures the race condition of concurrent success/failure recording with no prior history.
     */
    public ConcurrentOutcomeRecordingTest() {
    }

    @Actor
    public void actor1() {
        breaker.outcome(() -> "ok");
    }

    @Actor
    public void actor2() {
        breaker.outcome(() -> {
            throw new IllegalStateException("stress-induced failure");
        });
    }

    /**
     * Captures the final state: {@code r.r1 = recordedCount} (expect 2: one success, one
     * failure recorded atomically) and {@code r.r2 = finalRateObservedCount} (expect ≥1: at
     * least one reader observes the correct 0.5 failure rate, verifying window consistency).
     */
    @Arbiter
    public void arbiter(II_Result r) {
        r.r1 = recordedCount.get();
        r.r2 = finalRateObservedCount.get();
    }
}
