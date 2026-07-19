package io.github.teceli.resiliencia.metrics;

import io.github.teceli.resiliencia.metrics.retry.RetryCounters;
import io.github.teceli.resiliencia.patterns.retry.RetryEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResilienceMetricsListener} holds no internal per-name registry of its own — see
 * {@code resolveCause} in the production class — every {@code onEvent(...)} call is stateless
 * apart from reading the immutable {@code causeAllowlist}. So the meaningful concurrency scenario
 * for this module is not "a map race inside resiliencia-metrics" (there is none), but the
 * realistic production scenario: several resiliencia patterns sharing one listener instance and
 * invoking {@code onEvent(...)} concurrently, for the same name and for different names. These
 * tests verify that under contention every event still reaches the backend exactly once — no lost
 * updates, no duplicate forwarding, and correct per-name/per-cause classification.
 */
class ResilienceMetricsListenerConcurrencyTest {

    @Test
    void should_recordEveryAttemptFailedWithoutLoss_when_manyThreadsEmitConcurrentlyForSameAndDifferentNames()
        throws InterruptedException {
        // Arrange
        var countersByName = new ConcurrentHashMap<String, LongAdder>();
        ResilienceMetrics metrics = new ResilienceMetrics() {
            @Override
            public void observe(Snapshot snapshot) {
                // Not exercised: RetryEvent.AttemptFailed never produces a Snapshot.
            }

            @Override
            public void observe(Counters counters) {
                var attemptFailed = (RetryCounters.AttemptFailed) counters;
                countersByName.computeIfAbsent(attemptFailed.name(), key -> new LongAdder()).increment();
            }
        };
        var listener = new ResilienceMetricsListener(metrics);
        var names = List.of("retryA", "retryB", "retryC");
        var threadsPerName = 50;
        var eventsPerThread = 20;
        var ready = new CountDownLatch(1);
        var done = new CountDownLatch(names.size() * threadsPerName);

        // Act
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var name : names) {
                for (var t = 0; t < threadsPerName; t++) {
                    executor.submit(() -> {
                        try {
                            ready.await();
                            for (var i = 0; i < eventsPerThread; i++) {
                                listener.onEvent(new RetryEvent.AttemptFailed(Instant.now(), name, i,
                                    new RuntimeException("boom")));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
            }
            ready.countDown();
            var completedInTime = done.await(10, TimeUnit.SECONDS);

            // Assert
            assertThat(completedInTime).isTrue();
            assertThat(countersByName.keySet()).containsExactlyInAnyOrderElementsOf(names);
            for (var name : names) {
                assertThat(countersByName.get(name).sum()).isEqualTo((long) threadsPerName * eventsPerThread);
            }
        }
    }

    @Test
    void should_resolveCauseCorrectlyForEveryEvent_when_concurrentThreadsUseMixedAllowlistedAndUnlistedExceptions()
        throws InterruptedException {
        // Arrange
        var allowlist = Set.<Class<? extends Throwable>>of(IllegalStateException.class);
        var causeCountsByName = new ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>>();
        ResilienceMetrics metrics = new ResilienceMetrics() {
            @Override
            public void observe(Snapshot snapshot) {
                // Not exercised: RetryEvent.AttemptFailed never produces a Snapshot.
            }

            @Override
            public void observe(Counters counters) {
                var attemptFailed = (RetryCounters.AttemptFailed) counters;
                causeCountsByName.computeIfAbsent(attemptFailed.name(), key -> new ConcurrentHashMap<>())
                    .computeIfAbsent(attemptFailed.cause(), key -> new LongAdder())
                    .increment();
            }
        };
        var listener = new ResilienceMetricsListener(metrics, allowlist);
        var names = List.of("retryA", "retryB");
        var threadsPerName = 30;
        var ready = new CountDownLatch(1);
        var done = new CountDownLatch(names.size() * threadsPerName);

        // Act
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var name : names) {
                for (var t = 0; t < threadsPerName; t++) {
                    var threadIndex = t;
                    executor.submit(() -> {
                        try {
                            ready.await();
                            var error = threadIndex % 2 == 0
                                ? new IllegalStateException("allowlisted")
                                : new RuntimeException("not allowlisted");
                            listener.onEvent(new RetryEvent.AttemptFailed(Instant.now(), name, 1, error));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
            }
            ready.countDown();
            var completedInTime = done.await(10, TimeUnit.SECONDS);

            // Assert
            assertThat(completedInTime).isTrue();
            for (var name : names) {
                var causeCounts = causeCountsByName.get(name);
                assertThat(causeCounts.get("IllegalStateException").sum()).isEqualTo(threadsPerName / 2);
                assertThat(causeCounts.get("other").sum()).isEqualTo(threadsPerName / 2);
            }
        }
    }
}
