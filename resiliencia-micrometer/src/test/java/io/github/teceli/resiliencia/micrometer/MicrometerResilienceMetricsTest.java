package io.github.teceli.resiliencia.micrometer;

import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.metrics.bulkhead.BulkheadCounters;
import io.github.teceli.resiliencia.metrics.bulkhead.BulkheadSnapshot;
import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerCounters;
import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerSnapshot;
import io.github.teceli.resiliencia.metrics.policy.PolicyCounters;
import io.github.teceli.resiliencia.metrics.ratelimiter.RateLimiterCounters;
import io.github.teceli.resiliencia.metrics.ratelimiter.RateLimiterSnapshot;
import io.github.teceli.resiliencia.metrics.retry.RetryCounters;
import io.github.teceli.resiliencia.metrics.timeout.TimeoutCounters;
import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreakerEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerResilienceMetricsTest {

    private SimpleMeterRegistry registry;
    private MicrometerResilienceMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerResilienceMetrics(registry);
    }

    @Nested
    class RetryCountersHandling {
        @Test
        void should_incrementAttempts_when_attemptFailedObserved() {
            metrics.observe(new RetryCounters.AttemptFailed("myRetry", "IllegalStateException"));

            assertThat(registry.get(MetricNames.RETRY_ATTEMPTS)
                .tag("name", "myRetry")
                .tag("cause", "IllegalStateException")
                .counter().count()).isEqualTo(1.0);
        }

        @Test
        void should_incrementSuccess_when_successObserved_without_totalAttemptsTag() {
            metrics.observe(new RetryCounters.Success("myRetry", 3));

            var counter = registry.get(MetricNames.RETRY_SUCCESS).tag("name", "myRetry").counter();
            assertThat(counter.count()).isEqualTo(1.0);
            assertThat(counter.getId().getTags()).hasSize(1);
        }

        @Test
        void should_omitCauseTag_when_causeIsNull() {
            metrics.observe(new RetryCounters.Exhausted("myRetry", null));

            var counter = registry.get(MetricNames.RETRY_EXHAUSTED).tag("name", "myRetry").counter();
            assertThat(counter.getId().getTags()).hasSize(1);
        }

        @Test
        void should_incrementRejected_when_rejectedObserved() {
            metrics.observe(new RetryCounters.Rejected("myRetry", "BulkheadFullException"));

            assertThat(registry.get(MetricNames.RETRY_REJECTED)
                .tag("name", "myRetry")
                .tag("cause", "BulkheadFullException")
                .counter().count()).isEqualTo(1.0);
        }

        @Test
        void should_incrementInterrupted_when_interruptedObserved() {
            metrics.observe(new RetryCounters.Interrupted("myRetry", "InterruptedException"));

            assertThat(registry.get(MetricNames.RETRY_INTERRUPTED)
                .tag("name", "myRetry")
                .tag("cause", "InterruptedException")
                .counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    class TimeoutCountersHandling {
        @Test
        void should_recordDuration_when_succeededObserved() {
            metrics.observe(new TimeoutCounters.Succeeded("myTimeout", Duration.ofMillis(150)));

            var timer = registry.get(MetricNames.TIMEOUT_DURATION).tag("name", "myTimeout").timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(150.0);
        }

        @Test
        void should_incrementFailed_when_failedObserved() {
            metrics.observe(new TimeoutCounters.Failed("myTimeout", "RuntimeException"));

            assertThat(registry.get(MetricNames.TIMEOUT_FAILED)
                .tag("name", "myTimeout")
                .tag("cause", "RuntimeException")
                .counter().count()).isEqualTo(1.0);
        }

        @Test
        void should_incrementTimedOut_when_timedOutObserved() {
            metrics.observe(new TimeoutCounters.TimedOut("myTimeout"));

            assertThat(registry.get(MetricNames.TIMEOUT_TIMED_OUT)
                .tag("name", "myTimeout").counter().count()).isEqualTo(1.0);
        }

        @Test
        void should_incrementAbandoned_when_abandonedObserved() {
            metrics.observe(new TimeoutCounters.Abandoned("myTimeout", TimeoutCounters.AbandonedOutcome.SUCCEEDED));

            assertThat(registry.get(MetricNames.TIMEOUT_ABANDONED)
                .tag("name", "myTimeout")
                .tag("outcome", "SUCCEEDED")
                .counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    class CircuitBreakerCountersHandling {
        @Test
        void should_tagReason_when_transitionToOpen() {
            metrics.observe(new CircuitBreakerCounters.Transition(
                "myCb", CircuitBreakerSnapshot.Phase.OPEN, CircuitBreakerEvent.Reason.FAILURE_RATE_EXCEEDED));

            assertThat(registry.get(MetricNames.CIRCUITBREAKER_TRANSITIONS)
                .tag("name", "myCb")
                .tag("to", "OPEN")
                .tag("reason", "FAILURE_RATE_EXCEEDED")
                .counter().count()).isEqualTo(1.0);
        }

        @Test
        void should_omitReasonTag_when_transitionToClosed() {
            metrics.observe(new CircuitBreakerCounters.Transition("myCb", CircuitBreakerSnapshot.Phase.CLOSED, null));

            var counter = registry.get(MetricNames.CIRCUITBREAKER_TRANSITIONS)
                .tag("name", "myCb").tag("to", "CLOSED").counter();
            assertThat(counter.getId().getTags()).hasSize(2);
        }

        @Test
        void should_incrementByTestCalls_when_closedFromHalfOpenObserved() {
            metrics.observe(new CircuitBreakerCounters.ClosedFromHalfOpen("myCb", 5));

            assertThat(registry.get(MetricNames.CIRCUITBREAKER_CLOSED_TEST_CALLS)
                .tag("name", "myCb").counter().count()).isEqualTo(5.0);
        }

        @Test
        void should_recordTimer_when_callRecordedObserved() {
            metrics.observe(new CircuitBreakerCounters.CallRecorded("myCb", true, Duration.ofMillis(20)));

            var timer = registry.get(MetricNames.CIRCUITBREAKER_CALLS)
                .tag("name", "myCb").tag("successful", "true").timer();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        void should_tagPhase_when_rejectedObserved() {
            metrics.observe(new CircuitBreakerCounters.Rejected("myCb", CircuitBreakerEvent.RejectingPhase.HALF_OPEN));

            assertThat(registry.get(MetricNames.CIRCUITBREAKER_REJECTED)
                .tag("name", "myCb")
                .tag("phase", "HALF_OPEN")
                .counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    class BulkheadCountersHandling {
        @Test
        void should_tagOutcome_when_callObserved() {
            metrics.observe(new BulkheadCounters.Call("myBulkhead", BulkheadCounters.Outcome.PERMITTED));

            assertThat(registry.get(MetricNames.BULKHEAD_CALLS)
                .tag("name", "myBulkhead")
                .tag("outcome", "PERMITTED")
                .counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    class RateLimiterCountersHandling {
        @Test
        void should_tagOutcome_when_callObserved() {
            metrics.observe(new RateLimiterCounters.Call("myLimiter", RateLimiterCounters.Outcome.REJECTED));

            assertThat(registry.get(MetricNames.RATELIMITER_CALLS)
                .tag("name", "myLimiter")
                .tag("outcome", "REJECTED")
                .counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    class PolicyCountersHandling {
        @Test
        void should_tagOuterAndInner_when_validationWarningObserved() {
            metrics.observe(new PolicyCounters.ValidationWarning(PatternKind.RETRY, PatternKind.CIRCUIT_BREAKER));

            assertThat(registry.get(MetricNames.POLICY_VALIDATION_WARNINGS)
                .tag("outer", "RETRY")
                .tag("inner", "CIRCUIT_BREAKER")
                .counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    class SnapshotHandling {
        @Test
        void should_exposePhaseAsOrdinal_when_stateObserved() {
            metrics.observe(new CircuitBreakerSnapshot.State("myCb", CircuitBreakerSnapshot.Phase.HALF_OPEN));

            assertThat(registry.get(MetricNames.CIRCUITBREAKER_STATE)
                .tag("name", "myCb").gauge().value()).isEqualTo(2.0);
        }

        @Test
        void should_overwritePreviousValue_when_stateObservedTwice() {
            metrics.observe(new CircuitBreakerSnapshot.State("myCb", CircuitBreakerSnapshot.Phase.CLOSED));
            metrics.observe(new CircuitBreakerSnapshot.State("myCb", CircuitBreakerSnapshot.Phase.OPEN));

            assertThat(registry.get(MetricNames.CIRCUITBREAKER_STATE)
                .tag("name", "myCb").gauge().value()).isEqualTo(1.0);
        }

        @Test
        void should_mirrorFailureRate_when_failureRateObserved() {
            metrics.observe(new CircuitBreakerSnapshot.FailureRate("myCb", 0.42));

            assertThat(registry.get(MetricNames.CIRCUITBREAKER_FAILURE_RATE)
                .tag("name", "myCb").gauge().value()).isEqualTo(0.42);
        }

        @Test
        void should_mirrorActiveCalls_when_activeCallsObserved() {
            metrics.observe(new BulkheadSnapshot.ActiveCalls("myBulkhead", 3));

            assertThat(registry.get(MetricNames.BULKHEAD_ACTIVE_CALLS)
                .tag("name", "myBulkhead").gauge().value()).isEqualTo(3.0);
        }

        @Test
        void should_mirrorRemainingPermits_when_remainingPermitsObserved() {
            metrics.observe(new RateLimiterSnapshot.RemainingPermits("myLimiter", 7));

            assertThat(registry.get(MetricNames.RATELIMITER_REMAINING_PERMITS)
                .tag("name", "myLimiter").gauge().value()).isEqualTo(7.0);
        }
    }
}
