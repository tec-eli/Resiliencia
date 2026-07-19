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
 * {@link ResilienceMetricsListener} itself holds no per-name state, so these tests exercise the real
 * concurrency scenario instead: several patterns sharing one listener instance and calling
 * {@code onEvent(...)} concurrently, for the same and for different names.
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
            assertThat(completedInTime).as("all threads should finish emitting within the timeout").isTrue();
            assertThat(countersByName.keySet())
                .as("every name should reach the backend, even under contention")
                .containsExactlyInAnyOrderElementsOf(names);
            for (var name : names) {
                assertThat(countersByName.get(name).sum())
                    .as("no event for '%s' should be lost or duplicated across concurrent threads", name)
                    .isEqualTo((long) threadsPerName * eventsPerThread);
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
            assertThat(completedInTime).as("all threads should finish emitting within the timeout").isTrue();
            for (var name : names) {
                var causeCounts = causeCountsByName.get(name);
                assertThat(causeCounts.get("IllegalStateException").sum())
                    .as("allowlisted causes should be classified correctly under concurrent access")
                    .isEqualTo(threadsPerName / 2);
                assertThat(causeCounts.get("other").sum())
                    .as("non-allowlisted causes should fall into the 'other' bucket under concurrent access")
                    .isEqualTo(threadsPerName / 2);
            }
        }
    }
}
