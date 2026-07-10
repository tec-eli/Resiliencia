package io.github.teceli.resiliencia.stress.bulkhead;

import io.github.teceli.resiliencia.patterns.bulkhead.Bulkhead;
import io.github.teceli.resiliencia.core.api.Outcome;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that when a Bulkhead has no available permits, excess calls are
 * correctly rejected under concurrent contention.
 *
 * This test configures a Bulkhead with a limit of 1 concurrent call. Four
 * actors race to execute operations. Exactly one must succeed; the others
 * must be rejected, regardless of execution ordering and contention.
 */
@JCStressTest
@State
@org.openjdk.jcstress.annotations.Outcome(id = "1, 3", expect = Expect.ACCEPTABLE,
         desc = "Exactly one call admitted, three calls rejected.")
@org.openjdk.jcstress.annotations.Outcome(expect = Expect.FORBIDDEN,
         desc = "Unexpected: more than one call admitted, or mismatch in total calls.")
public class RejectionUnderContentionTest {

    private static final int MAX_CONCURRENT = 1;

    private final AtomicInteger admittedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);

    private final Bulkhead<String> bulkhead = Bulkhead.<String>of(
        "stress-rejection-under-contention", MAX_CONCURRENT);

    /**
     * Attempts to execute an operation through the bulkhead. Counts whether
     * the call was admitted (executed) or rejected (no permits available).
     */
    private void attemptCall() {
        var outcome = bulkhead.outcome(() -> {
            // Spin briefly so the bulkhead stays busy while other actors arrive
            spinBriefly();
            return "done";
        });

        if (outcome instanceof Outcome.Success<?>) {
            admittedCount.incrementAndGet();
        } else {
            // Any failure is a rejection for this test
            rejectedCount.incrementAndGet();
        }
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

    /**
     * Captures the final counts: {@code r.r1 = admittedCount} (expect 1: only
     * one call should succeed) and {@code r.r2 = rejectedCount} (expect 3:
     * the other three actors should be rejected by the bulkhead).
     */
    @Arbiter
    public void arbiter(II_Result r) {
        r.r1 = admittedCount.get();
        r.r2 = rejectedCount.get();
    }
}
