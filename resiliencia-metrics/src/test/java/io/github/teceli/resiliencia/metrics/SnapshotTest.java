package io.github.teceli.resiliencia.metrics;

import io.github.teceli.resiliencia.metrics.bulkhead.BulkheadSnapshot;
import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerSnapshot;
import io.github.teceli.resiliencia.metrics.ratelimiter.RateLimiterSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotTest {

    @Test
    void should_permitOnlyPatternsWithLiveState_when_sealedPermitsInspected() {
        assertThat(Snapshot.class.getPermittedSubclasses())
            .containsExactlyInAnyOrder(CircuitBreakerSnapshot.class, BulkheadSnapshot.class,
                RateLimiterSnapshot.class);
    }
}
