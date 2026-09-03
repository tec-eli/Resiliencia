package io.github.teceli.resiliencia.micrometer;

import io.github.teceli.resiliencia.metrics.bulkhead.BulkheadSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerResilienceMetricsConcurrencyTest {

    @Test
    void should_registerExactlyOneGauge_when_manyThreadsRaceToRegisterTheSameNameConcurrently()
        throws InterruptedException {
        // Arrange
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerResilienceMetrics(registry);
        var threadCount = 200;
        var ready = new CountDownLatch(1);
        var done = new CountDownLatch(threadCount);

        // Act
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var t = 0; t < threadCount; t++) {
                var value = t;
                executor.submit(() -> {
                    try {
                        ready.await();
                        metrics.observe(new BulkheadSnapshot.ActiveCalls("sharedName", value));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.countDown();
            var completedInTime = done.await(10, TimeUnit.SECONDS);

            // Assert
            assertThat(completedInTime).as("all threads should finish emitting within the timeout").isTrue();
            assertThat(registry.find(MetricNames.BULKHEAD_ACTIVE_CALLS).gauges())
                .as("concurrent registration attempts for the same name must not create duplicate gauges")
                .hasSize(1);
        }
    }

    @Test
    void should_registerEveryDistinctName_when_manyThreadsRegisterDifferentNamesConcurrentlyUnderTheBound()
        throws InterruptedException {
        // Arrange
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerResilienceMetrics(registry);
        var threadCount = 200;
        var ready = new CountDownLatch(1);
        var done = new CountDownLatch(threadCount);

        // Act
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var t = 0; t < threadCount; t++) {
                var index = t;
                executor.submit(() -> {
                    try {
                        ready.await();
                        metrics.observe(new BulkheadSnapshot.ActiveCalls("bulkhead-" + index, index));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.countDown();
            var completedInTime = done.await(10, TimeUnit.SECONDS);

            // Assert
            assertThat(completedInTime).as("all threads should finish emitting within the timeout").isTrue();
            assertThat(registry.find(MetricNames.BULKHEAD_ACTIVE_CALLS).gauges())
                .as("every distinct name under the cache bound should get its own gauge, even under contention")
                .hasSize(threadCount);
        }
    }

    @Test
    void should_boundGaugeCacheDeterministically_when_manyThreadsExceedTheBoundConcurrently()
        throws InterruptedException {
        // Arrange
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerResilienceMetrics(registry);
        var extraNames = 50;
        var threadCount = MicrometerResilienceMetrics.MAX_GAUGE_CACHE_ENTRIES + extraNames;
        var ready = new CountDownLatch(1);
        var done = new CountDownLatch(threadCount);

        // Act
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var t = 0; t < threadCount; t++) {
                var index = t;
                executor.submit(() -> {
                    try {
                        ready.await();
                        metrics.observe(new BulkheadSnapshot.ActiveCalls("bulkhead-" + index, index));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.countDown();
            var completedInTime = done.await(30, TimeUnit.SECONDS);

            // Assert
            assertThat(completedInTime).as("all threads should finish emitting within the timeout").isTrue();
            var registeredGauges = registry.find(MetricNames.BULKHEAD_ACTIVE_CALLS).gauges().size();
            assertThat(registeredGauges)
                .as("the lock-free bound check may let a small number of concurrent racers past the boundary "
                    + "before it settles, but it must stay close to the documented bound, never grow toward the "
                    + "full number of distinct names attempted (%d)", threadCount)
                .isLessThanOrEqualTo(MicrometerResilienceMetrics.MAX_GAUGE_CACHE_ENTRIES + extraNames)
                .isGreaterThanOrEqualTo(MicrometerResilienceMetrics.MAX_GAUGE_CACHE_ENTRIES);
        }
    }
}
