package io.github.teceli.resiliencia.stress.bulkhead;

import io.github.teceli.resiliencia.patterns.bulkhead.Bulkhead;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that under heavy contention, a Bulkhead never allows more than
 * the configured maximum of concurrent calls to execute simultaneously.
 *
 * This test configures a Bulkhead with a limit of 2 concurrent calls. Six
 * actors race to execute operations through the Bulkhead. Inside each
 * operation, an atomic counter tracks how many are executing in parallel.
 * The test verifies that the peak concurrent count never exceeds 2,
 * regardless of execution ordering and contention.
 */
@JCStressTest
@State
@Outcome(id = "2", expect = Expect.ACCEPTABLE, desc = "Max concurrent calls respected: at most 2.")
@Outcome(expect = Expect.FORBIDDEN, desc = "Concurrency limit violated: more than 2 concurrent calls.")
public class MaxConcurrentCallsLimitTest {

    private static final int MAX_CONCURRENT = 2;

    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger maxObserved = new AtomicInteger(0);

    private final Bulkhead<String> bulkhead = Bulkhead.<String>of(
        "stress-max-concurrent-limit", MAX_CONCURRENT);

    /**
     * Attempts to execute an operation through the bulkhead. Inside the
     * operation, increments the in-flight counter, records the maximum
     * observed concurrency, then decrements.
     */
    private void attemptCall() {
        bulkhead.outcome(() -> {
            int current = inFlight.incrementAndGet();
            maxObserved.accumulateAndGet(current, Math::max);
            // Spin briefly to allow other actors to observe concurrent execution
            spinBriefly();
            inFlight.decrementAndGet();
            return "done";
        });
    }

    /**
     * Yields briefly to allow thread scheduler to interleave other actors.
     */
    private static void spinBriefly() {
        for (int i = 0; i < 1000; i++) {
            Thread.onSpinWait();
        }
    }

    @Actor
    public void actor1() {
        attemptCall();
    }

    @Actor
    public void actor2() {
        attemptCall();
    }

    @Actor
    public void actor3() {
        attemptCall();
    }

    @Actor
    public void actor4() {
        attemptCall();
    }

    @Actor
    public void actor5() {
        attemptCall();
    }

    @Actor
    public void actor6() {
        attemptCall();
    }

    /**
     * Captures the maximum observed concurrent calls. This should never exceed
     * the configured limit of 2.
     */
    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = maxObserved.get();
    }
}
