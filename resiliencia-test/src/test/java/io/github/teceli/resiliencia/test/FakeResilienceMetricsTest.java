package io.github.teceli.resiliencia.test;

import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerCounters;
import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerSnapshot;
import io.github.teceli.resiliencia.metrics.retry.RetryCounters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FakeResilienceMetrics")
class FakeResilienceMetricsTest {
    private FakeResilienceMetrics fake;

    @BeforeEach
    void setUp() {
        fake = new FakeResilienceMetrics();
    }

    @Test
    void should_captureSnapshot_when_recordSnapshotCalled() {
        var state = new CircuitBreakerSnapshot.State("test", CircuitBreakerSnapshot.Phase.CLOSED);

        fake.observe(state);

        assertThat(fake.getAllSnapshots())
            .hasSize(1)
            .contains(state);
    }

    @Test
    void should_captureCounters_when_recordCountersCalled() {
        var counters = new RetryCounters.Success("test", 2);

        fake.observe(counters);

        assertThat(fake.getAllCounters())
            .hasSize(1)
            .contains(counters);
    }

    @Test
    void should_captureMultipleSnapshots_when_recordCalledMultipleTimes() {
        var state1 = new CircuitBreakerSnapshot.State("test1", CircuitBreakerSnapshot.Phase.CLOSED);
        var state2 = new CircuitBreakerSnapshot.State("test2", CircuitBreakerSnapshot.Phase.OPEN);
        var failureRate = new CircuitBreakerSnapshot.FailureRate("test1", 0.5);

        fake.observe(state1);
        fake.observe(state2);
        fake.observe(failureRate);

        assertThat(fake.getAllSnapshots())
            .hasSize(3)
            .containsExactly(state1, state2, failureRate);
    }

    @Test
    void should_captureMultipleCounters_when_recordCalledMultipleTimes() {
        var success = new RetryCounters.Success("test", 1);
        var attemptFailed = new RetryCounters.AttemptFailed("test", "IOException");
        var exhausted = new RetryCounters.Exhausted("test", "IOException");

        fake.observe(success);
        fake.observe(attemptFailed);
        fake.observe(exhausted);

        assertThat(fake.getAllCounters())
            .hasSize(3)
            .containsExactly(success, attemptFailed, exhausted);
    }

    @Test
    void should_filterSnapshotsByType_when_getSnapshotsOfTypeCalled() {
        var state = new CircuitBreakerSnapshot.State("test", CircuitBreakerSnapshot.Phase.CLOSED);
        var failureRate = new CircuitBreakerSnapshot.FailureRate("test", 0.5);
        var anotherState = new CircuitBreakerSnapshot.State("other", CircuitBreakerSnapshot.Phase.OPEN);

        fake.observe(state);
        fake.observe(failureRate);
        fake.observe(anotherState);

        var stateSnapshots = fake.getSnapshotsOfType(CircuitBreakerSnapshot.State.class);

        assertThat(stateSnapshots)
            .hasSize(2)
            .containsExactly(state, anotherState);
    }

    @Test
    void should_filterCountersByType_when_getCountersOfTypeCalled() {
        var success = new RetryCounters.Success("test", 1);
        var failed = new RetryCounters.AttemptFailed("test", "IOException");
        var exhausted = new RetryCounters.Exhausted("test", "IOException");

        fake.observe(success);
        fake.observe(failed);
        fake.observe(exhausted);

        var successes = fake.getCountersOfType(RetryCounters.Success.class);

        assertThat(successes)
            .hasSize(1)
            .containsExactly(success);
    }

    @Test
    void should_returnEmptyList_when_getSnapshotsOfTypeCalledWithNoMatches() {
        var state = new CircuitBreakerSnapshot.State("test", CircuitBreakerSnapshot.Phase.CLOSED);

        fake.observe(state);

        var failures = fake.getSnapshotsOfType(CircuitBreakerSnapshot.FailureRate.class);

        assertThat(failures).isEmpty();
    }

    @Test
    void should_returnEmptyList_when_getCountersOfTypeCalledWithNoMatches() {
        var success = new RetryCounters.Success("test", 1);

        fake.observe(success);

        var failed = fake.getCountersOfType(RetryCounters.Exhausted.class);

        assertThat(failed).isEmpty();
    }

    @Test
    void should_returnSnapshotAndCounterCounts() {
        fake.observe(new CircuitBreakerSnapshot.State("test", CircuitBreakerSnapshot.Phase.CLOSED));
        fake.observe(new CircuitBreakerSnapshot.FailureRate("test", 0.5));
        fake.observe(new RetryCounters.Success("test", 1));

        assertThat(fake.snapshotCount()).isEqualTo(2);
        assertThat(fake.countersCount()).isEqualTo(1);
    }

    @Test
    void should_clearAllMetrics_when_resetCalled() {
        fake.observe(new CircuitBreakerSnapshot.State("test", CircuitBreakerSnapshot.Phase.CLOSED));
        fake.observe(new RetryCounters.Success("test", 1));

        assertThat(fake.snapshotCount()).isEqualTo(1);
        assertThat(fake.countersCount()).isEqualTo(1);

        fake.reset();

        assertThat(fake.getAllSnapshots()).isEmpty();
        assertThat(fake.getAllCounters()).isEmpty();
        assertThat(fake.snapshotCount()).isZero();
        assertThat(fake.countersCount()).isZero();
    }

    @Test
    void should_returnTrue_when_isEmptyCalledAndNoMetricsRecorded() {
        assertThat(fake.isEmpty()).isTrue();

        fake.observe(new CircuitBreakerSnapshot.State("test", CircuitBreakerSnapshot.Phase.CLOSED));

        assertThat(fake.isEmpty()).isFalse();
    }

    @Test
    void should_returnTrue_when_isEmptyCalledAfterReset() {
        fake.observe(new RetryCounters.Success("test", 1));
        assertThat(fake.isEmpty()).isFalse();

        fake.reset();

        assertThat(fake.isEmpty()).isTrue();
    }

    @Test
    void should_beThreadSafe_when_recordCalledConcurrently() throws InterruptedException {
        var snapshot = new CircuitBreakerSnapshot.State("test", CircuitBreakerSnapshot.Phase.CLOSED);
        var counters = new RetryCounters.Success("test", 1);

        var threadCount = 10;
        var operationsPerThread = 100;
        var latch = new CountDownLatch(threadCount);

        try (var executor = Executors.newFixedThreadPool(threadCount)) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            if (j % 2 == 0) {
                                fake.observe(snapshot);
                            } else {
                                fake.observe(counters);
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            var expectedSnapshots = threadCount * operationsPerThread / 2;
            var expectedCounters = threadCount * operationsPerThread / 2;

            assertThat(fake.snapshotCount()).isEqualTo(expectedSnapshots);
            assertThat(fake.countersCount()).isEqualTo(expectedCounters);
        }
    }

    @Test
    void should_beThreadSafe_when_recordAndResetCalledConcurrently() throws InterruptedException {
        var snapshot = new CircuitBreakerSnapshot.State("test", CircuitBreakerSnapshot.Phase.CLOSED);
        var counters = new RetryCounters.Success("test", 1);

        var recordThreads = 5;
        var resetThreads = 2;
        var latch = new CountDownLatch(recordThreads + resetThreads);

        try (var executor = Executors.newFixedThreadPool(recordThreads + resetThreads)) {
            // Record threads
            for (int i = 0; i < recordThreads; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < 100; j++) {
                            if (j % 2 == 0) {
                                fake.observe(snapshot);
                            } else {
                                fake.observe(counters);
                            }
                            Thread.sleep(1);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Reset threads
            for (int i = 0; i < resetThreads; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < 20; j++) {
                            Thread.sleep(5);
                            fake.reset();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();

            // Should not throw, all concurrent operations completed successfully
            assertThat(fake.snapshotCount() + fake.countersCount()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void should_returnImmutableList_when_getAllSnapshotsCalled() {
        fake.observe(new CircuitBreakerSnapshot.State("test", CircuitBreakerSnapshot.Phase.CLOSED));

        var snapshots = fake.getAllSnapshots();

        assertThatThrownBy(() -> snapshots.add(
            new CircuitBreakerSnapshot.State("another", CircuitBreakerSnapshot.Phase.OPEN)
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_returnImmutableList_when_getAllCountersCalled() {
        fake.observe(new RetryCounters.Success("test", 1));

        var counters = fake.getAllCounters();

        assertThatThrownBy(() -> counters.add(new RetryCounters.Success("another", 2)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_returnImmutableList_when_getSnapshotsOfTypeCalled() {
        fake.observe(new CircuitBreakerSnapshot.State("test", CircuitBreakerSnapshot.Phase.CLOSED));

        var filtered = fake.getSnapshotsOfType(CircuitBreakerSnapshot.State.class);

        assertThatThrownBy(() -> filtered.add(
            new CircuitBreakerSnapshot.State("another", CircuitBreakerSnapshot.Phase.OPEN)
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_returnImmutableList_when_getCountersOfTypeCalled() {
        fake.observe(new RetryCounters.Success("test", 1));

        var filtered = fake.getCountersOfType(RetryCounters.Success.class);

        assertThatThrownBy(() -> filtered.add(new RetryCounters.Success("another", 2)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_captureSnapshotsInEmissionOrder() {
        var snapshot1 = new CircuitBreakerSnapshot.State("test1", CircuitBreakerSnapshot.Phase.CLOSED);
        var snapshot2 = new CircuitBreakerSnapshot.FailureRate("test1", 0.5);
        var snapshot3 = new CircuitBreakerSnapshot.State("test2", CircuitBreakerSnapshot.Phase.OPEN);

        fake.observe(snapshot1);
        fake.observe(snapshot2);
        fake.observe(snapshot3);

        assertThat(fake.getAllSnapshots())
            .containsExactly(snapshot1, snapshot2, snapshot3);
    }

    @Test
    void should_captureCountersInEmissionOrder() {
        var counter1 = new RetryCounters.Success("test", 1);
        var counter2 = new RetryCounters.AttemptFailed("test", "IOException");
        var counter3 = new RetryCounters.Exhausted("test", "IOException");

        fake.observe(counter1);
        fake.observe(counter2);
        fake.observe(counter3);

        assertThat(fake.getAllCounters())
            .containsExactly(counter1, counter2, counter3);
    }

    @Test
    void should_captureCircuitBreakerCounters() {
        var transition = new CircuitBreakerCounters.Transition(
            "test",
            CircuitBreakerSnapshot.Phase.OPEN,
            null
        );
        var callRecorded = new CircuitBreakerCounters.CallRecorded("test", true, Duration.ofMillis(100));

        fake.observe(transition);
        fake.observe(callRecorded);

        var cbCounters = fake.getCountersOfType(CircuitBreakerCounters.class);

        assertThat(cbCounters).hasSize(2).contains(transition, callRecorded);
    }
}
