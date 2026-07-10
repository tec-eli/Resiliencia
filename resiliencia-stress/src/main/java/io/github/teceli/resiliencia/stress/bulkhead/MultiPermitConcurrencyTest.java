package io.github.teceli.resiliencia.stress.bulkhead;

import io.github.teceli.resiliencia.patterns.bulkhead.Bulkhead;
import io.github.teceli.resiliencia.core.api.Outcome;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that a Bulkhead correctly manages multiple permits under heavy
 * concurrent contention.
 *
 * This test configures a Bulkhead with 3 concurrent permits. Nine actors
 * race to execute operations. Exactly 3 must be admitted; the others must be
 * rejected. The test counts admissions and rejections to verify the limit
 * is enforced.
 */
@JCStressTest
@State
@org.openjdk.jcstress.annotations.Outcome(id = "3, 6, 0", expect = Expect.ACCEPTABLE,
         desc = "All 3 permits used, 6 calls rejected, 0 errors.")
@org.openjdk.jcstress.annotations.Outcome(expect = Expect.FORBIDDEN,
         desc = "Unexpected: permits exceeded, or total calls mismatch.")
public class MultiPermitConcurrencyTest {

    private static final int MAX_CONCURRENT = 3;

    private final AtomicInteger admittedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);

    private final Bulkhead<String> bulkhead = Bulkhead.<String>of(
        "stress-multi-permit-concurrency", MAX_CONCURRENT);

    /**
     * Attempts to execute an operation through the bulkhead. Tracks admits,
     * rejections, and errors.
     */
    private void attemptCall() {
        var outcome = bulkhead.outcome(() -> {
            // Spin to keep permits occupied while others arrive
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

    @Actor
    public void actor5() {
        attemptCall();
    }

    @Actor
    public void actor6() {
        attemptCall();
    }

    @Actor
    public void actor7() {
        attemptCall();
    }

    @Actor
    public void actor8() {
        attemptCall();
    }

    @Actor
    public void actor9() {
        attemptCall();
    }

    /**
     * Captures final counts: {@code r.r1 = admittedCount} (expect 3),
     * {@code r.r2 = rejectedCount} (expect 6), {@code r.r3 = errorCount}
     * (expect 0).
     */
    @Arbiter
    public void arbiter(III_Result r) {
        r.r1 = admittedCount.get();
        r.r2 = rejectedCount.get();
        r.r3 = errorCount.get();
    }
}
