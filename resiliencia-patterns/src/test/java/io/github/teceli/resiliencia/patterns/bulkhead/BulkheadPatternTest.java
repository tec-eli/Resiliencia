package io.github.teceli.resiliencia.patterns.bulkhead;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.ResilienciaException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for the Bulkhead pattern: concurrency limiting, fail-fast and bounded-wait
 * rejection, permit release, outcome mapping, and event emission.
 */
class BulkheadPatternTest {

    @Test
    void should_returnValue_when_underConcurrencyLimit() {
        var bulkhead = Bulkhead.<String>of(2);

        var result = bulkhead.call(() -> "done");

        assertThat(result).isEqualTo("done");
    }

    @Test
    void should_throwBulkheadFullException_when_allPermitsInUse() throws Exception {
        var bulkhead = Bulkhead.<String>of(1);

        withPermitHeld(bulkhead, () ->
                assertThatExceptionOfType(BulkheadFullException.class)
                        .isThrownBy(() -> bulkhead.call(() -> "rejected"))
                        .satisfies(e -> {
                            assertThat(e.maxConcurrentCalls()).isEqualTo(1);
                            assertThat(e.maxWait()).isEqualTo(Duration.ZERO);
                        }));
    }

    @Test
    void should_returnFailureWithBulkheadFullException_when_usingOutcomeMethod() throws Exception {
        var bulkhead = Bulkhead.<String>of(1);

        withPermitHeld(bulkhead, () -> {
            var outcome = bulkhead.outcome(() -> "rejected");

            assertThat(outcome)
                    .isInstanceOfSatisfying(Outcome.Failure.class, f ->
                            assertThat(f.cause()).isInstanceOf(BulkheadFullException.class));
        });
    }

    @Test
    void should_waitForPermit_when_maxWaitConfigured() throws Exception {
        var bulkhead = Bulkhead.<String>of(1).withMaxWait(Duration.ofSeconds(5));
        var holderInside = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);

        var holder = Thread.ofVirtual().start(() -> bulkhead.call(() -> {
            holderInside.countDown();
            awaitQuietly(releaseHolder);
            return "holder";
        }));
        assertThat(holderInside.await(5, TimeUnit.SECONDS)).isTrue();

        var waiterResult = new AtomicReference<String>();
        var waiter = Thread.ofVirtual().start(() ->
                waiterResult.set(bulkhead.call(() -> "waited")));

        // Free the permit shortly after the waiter started queuing for it.
        Thread.sleep(50);
        releaseHolder.countDown();

        holder.join(Duration.ofSeconds(5));
        waiter.join(Duration.ofSeconds(5));
        assertThat(waiterResult.get()).isEqualTo("waited");
    }

    @Test
    void should_releasePermit_when_operationFails() {
        var bulkhead = Bulkhead.<String>of(1);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> bulkhead.call(() -> {
                    throw new IllegalStateException("boom");
                }));

        assertThat(bulkhead.call(() -> "recovered")).isEqualTo("recovered");
    }

    @Test
    void should_rethrowOriginalException_when_operationFails() {
        var bulkhead = Bulkhead.<String>of(1);
        var boom = new IllegalStateException("boom");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> bulkhead.call(() -> {
                    throw boom;
                }))
                .isSameAs(boom);
    }

    @Test
    void should_neverExceedConcurrencyLimit_when_calledFromManyThreads() throws Exception {
        var bulkhead = Bulkhead.<String>of(2).withMaxWait(Duration.ofSeconds(10));
        var inFlight = new AtomicInteger(0);
        var maxObserved = new AtomicInteger(0);

        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 10; i++) {
            threads.add(Thread.ofVirtual().start(() -> bulkhead.call(() -> {
                var current = inFlight.incrementAndGet();
                maxObserved.accumulateAndGet(current, Math::max);
                sleepQuietly(20);
                inFlight.decrementAndGet();
                return "done";
            })));
        }
        for (var thread : threads) {
            thread.join(Duration.ofSeconds(10));
        }

        assertThat(maxObserved.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void should_emitPermittedAndFinishedEvents_when_callSucceeds() {
        var events = new ArrayList<BulkheadEvent>();
        var bulkhead = Bulkhead.<String>of(1)
                .withListener(event -> events.add((BulkheadEvent) event));

        bulkhead.call(() -> "done");

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOfSatisfying(BulkheadEvent.Permitted.class,
                p -> assertThat(p.activeCalls()).isEqualTo(1));
        assertThat(events.get(1)).isInstanceOfSatisfying(BulkheadEvent.Finished.class,
                f -> assertThat(f.activeCalls()).isZero());
    }

    @Test
    void should_reportActiveCallCount_when_secondCallOverlapsFirst() throws Exception {
        var events = new ArrayList<BulkheadEvent>();
        var bulkhead = Bulkhead.<String>of(2)
                .withListener(event -> events.add((BulkheadEvent) event));
        var firstInside = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);

        var first = Thread.ofVirtual().start(() -> bulkhead.call(() -> {
            firstInside.countDown();
            awaitQuietly(releaseFirst);
            return "first";
        }));
        assertThat(firstInside.await(5, TimeUnit.SECONDS)).isTrue();

        bulkhead.call(() -> "second");

        releaseFirst.countDown();
        first.join(Duration.ofSeconds(5));

        var permitted = events.stream()
                .filter(BulkheadEvent.Permitted.class::isInstance)
                .map(BulkheadEvent.Permitted.class::cast)
                .toList();
        assertThat(permitted).hasSize(2);
        assertThat(permitted.get(0).activeCalls()).isEqualTo(1);
        assertThat(permitted.get(1).activeCalls()).isEqualTo(2);
    }

    @Test
    void should_emitRejectedEvent_when_bulkheadIsFull() throws Exception {
        var events = new ArrayList<BulkheadEvent>();
        var bulkhead = Bulkhead.<String>of(1)
                .withListener(event -> events.add((BulkheadEvent) event));

        withPermitHeld(bulkhead, () -> {
            bulkhead.outcome(() -> "rejected");

            // The permit holder emits its own Permitted event; the rejected call must add a Rejected one.
            assertThat(events).hasAtLeastOneElementOfType(BulkheadEvent.Rejected.class);
        });
    }

    @Test
    void should_reportBulkheadKind_when_patternKindQueried() {
        assertThat(Bulkhead.<String>of(1).patternKind()).isEqualTo(PatternKind.BULKHEAD);
        assertThat(Bulkhead.<String>of(1).patternName()).isEqualTo("bulkhead");
    }

    @Test
    void should_throwIllegalArgumentException_when_configurationInvalid() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Bulkhead.<String>of(0));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> Bulkhead.<String>of(1).withMaxWait(Duration.ofMillis(-1)));
        assertThatNullPointerException()
            .isThrownBy(() -> Bulkhead.<String>of(1).withMaxWait(null));
    }

    @Test
    void should_createIndependentInstanceWithFreshPermits_when_witherCalled() throws Exception {
        var original = Bulkhead.<String>of(1);

        var reconfigured = original.withMaxWait(Duration.ofMillis(5));

        assertThat(reconfigured).isNotSameAs(original);
        // The original's busy permit must not affect the reconfigured instance.
        withPermitHeld(original, () ->
                assertThat(reconfigured.call(() -> "independent")).isEqualTo("independent"));
    }

    /**
     * Runs the assertion while another virtual thread holds one of the bulkhead's permits,
     * then releases the holder and waits for it to finish.
     */
    private static void withPermitHeld(Bulkhead<String> bulkhead, Runnable assertion) throws InterruptedException {
        var holderInside = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holder = Thread.ofVirtual().start(() -> bulkhead.call(() -> {
            holderInside.countDown();
            awaitQuietly(releaseHolder);
            return "holder";
        }));
        assertThat(holderInside.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertion.run();
        } finally {
            releaseHolder.countDown();
            holder.join(Duration.ofSeconds(5));
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResilienciaException("interrupted", e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResilienciaException("interrupted", e);
        }
    }
}
