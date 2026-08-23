package io.github.teceli.resiliencia.metrics;

import io.github.teceli.resiliencia.metrics.bulkhead.BulkheadCounters;
import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerCounters;
import io.github.teceli.resiliencia.metrics.policy.PolicyCounters;
import io.github.teceli.resiliencia.metrics.ratelimiter.RateLimiterCounters;
import io.github.teceli.resiliencia.metrics.retry.RetryCounters;
import io.github.teceli.resiliencia.metrics.timeout.TimeoutCounters;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CountersTest {

    @Test
    void should_permitExactlyOneHierarchyPerPattern_when_sealedPermitsInspected() {
        assertThat(Counters.class.getPermittedSubclasses())
            .containsExactlyInAnyOrder(RetryCounters.class, TimeoutCounters.class,
                CircuitBreakerCounters.class, BulkheadCounters.class, RateLimiterCounters.class,
                PolicyCounters.class);
    }
}
