package io.github.teceli.resiliencia.metrics;

import io.github.teceli.resiliencia.metrics.bulkhead.BulkheadCounters;
import io.github.teceli.resiliencia.metrics.bulkhead.BulkheadSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class NoOpMetricsTest {

    @Test
    void should_discardSnapshotSilently_when_observeSnapshotCalled() {
        assertThatCode(() -> NoOpMetrics.INSTANCE.observe(new BulkheadSnapshot.ActiveCalls("myBulkhead", 3)))
            .doesNotThrowAnyException();
    }

    @Test
    void should_discardCountersSilently_when_observeCountersCalled() {
        assertThatCode(() ->
            NoOpMetrics.INSTANCE.observe(new BulkheadCounters.Call("myBulkhead", BulkheadCounters.Outcome.PERMITTED)))
            .doesNotThrowAnyException();
    }
}
