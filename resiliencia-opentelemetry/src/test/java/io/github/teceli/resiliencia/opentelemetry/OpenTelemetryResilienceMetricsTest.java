package io.github.teceli.resiliencia.opentelemetry;

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
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryResilienceMetricsTest {
    private static final AttributeKey<String> KEY_NAME = AttributeKey.stringKey("name");
    private static final AttributeKey<String> KEY_CAUSE = AttributeKey.stringKey("cause");
    private static final AttributeKey<String> KEY_OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> KEY_TO = AttributeKey.stringKey("to");
    private static final AttributeKey<String> KEY_REASON = AttributeKey.stringKey("reason");
    private static final AttributeKey<String> KEY_PHASE = AttributeKey.stringKey("phase");
    private static final AttributeKey<String> KEY_SUCCESSFUL = AttributeKey.stringKey("successful");
    private static final AttributeKey<String> KEY_OUTER = AttributeKey.stringKey("outer");
    private static final AttributeKey<String> KEY_INNER = AttributeKey.stringKey("inner");

    private RecordingMeter meter;
    private OpenTelemetryResilienceMetrics metrics;

    @BeforeEach
    void setUp() {
        meter = new RecordingMeter();
        metrics = new OpenTelemetryResilienceMetrics(meter);
    }

    @Nested
    class RetryCountersHandling {
        @Test
        void should_incrementAttempts_when_attemptFailedObserved() {
            metrics.observe(new RetryCounters.AttemptFailed("myRetry", "IllegalStateException"));

            assertThat(meter.counterTotal(MetricNames.RETRY_ATTEMPTS,
                Attributes.of(KEY_NAME, "myRetry", KEY_CAUSE, "IllegalStateException"))).isEqualTo(1.0);
        }

        @Test
        void should_incrementSuccess_when_successObserved_without_totalAttemptsTag() {
            metrics.observe(new RetryCounters.Success("myRetry", 3));

            assertThat(meter.counterTotal(MetricNames.RETRY_SUCCESS, Attributes.of(KEY_NAME, "myRetry"))).isEqualTo(1.0);
        }

        @Test
        void should_omitCauseTag_when_causeIsNull() {
            metrics.observe(new RetryCounters.Exhausted("myRetry", null));

            assertThat(meter.counterTotal(MetricNames.RETRY_EXHAUSTED, Attributes.of(KEY_NAME, "myRetry"))).isEqualTo(1.0);
        }

        @Test
        void should_incrementRejected_when_rejectedObserved() {
            metrics.observe(new RetryCounters.Rejected("myRetry", "BulkheadFullException"));

            assertThat(meter.counterTotal(MetricNames.RETRY_REJECTED,
                Attributes.of(KEY_NAME, "myRetry", KEY_CAUSE, "BulkheadFullException"))).isEqualTo(1.0);
        }

        @Test
        void should_incrementInterrupted_when_interruptedObserved() {
            metrics.observe(new RetryCounters.Interrupted("myRetry", "InterruptedException"));

            assertThat(meter.counterTotal(MetricNames.RETRY_INTERRUPTED,
                Attributes.of(KEY_NAME, "myRetry", KEY_CAUSE, "InterruptedException"))).isEqualTo(1.0);
        }
    }

    @Nested
    class TimeoutCountersHandling {
        @Test
        void should_recordSafeDurationPair_when_succeededObserved_and_defaultMode() {
            metrics.observe(new TimeoutCounters.Succeeded("myTimeout", Duration.ofMillis(150)));

            var attributes = Attributes.of(KEY_NAME, "myTimeout");
            assertThat(meter.counterTotal(MetricNames.TIMEOUT_DURATION_COUNT, attributes)).isEqualTo(1.0);
            assertThat(meter.doubleCounterTotal(MetricNames.TIMEOUT_DURATION_SUM, attributes)).isEqualTo(150.0);
            assertThat(meter.histogramPoints(MetricNames.TIMEOUT_DURATION)).isEmpty();
        }

        @Test
        void should_recordHistogram_when_succeededObserved_and_detailedMode() {
            metrics = new OpenTelemetryResilienceMetrics(meter, DurationInstrumentationMode.DETAILED);

            metrics.observe(new TimeoutCounters.Succeeded("myTimeout", Duration.ofMillis(150)));

            assertThat(meter.lastHistogram(MetricNames.TIMEOUT_DURATION, Attributes.of(KEY_NAME, "myTimeout")))
                .isEqualTo(150.0);
            assertThat(meter.counterPoints(MetricNames.TIMEOUT_DURATION_COUNT)).isEmpty();
        }

        @Test
        void should_incrementFailed_when_failedObserved() {
            metrics.observe(new TimeoutCounters.Failed("myTimeout", "RuntimeException"));

            assertThat(meter.counterTotal(MetricNames.TIMEOUT_FAILED,
                Attributes.of(KEY_NAME, "myTimeout", KEY_CAUSE, "RuntimeException"))).isEqualTo(1.0);
        }

        @Test
        void should_incrementTimedOut_when_timedOutObserved() {
            metrics.observe(new TimeoutCounters.TimedOut("myTimeout"));

            assertThat(meter.counterTotal(MetricNames.TIMEOUT_TIMED_OUT, Attributes.of(KEY_NAME, "myTimeout")))
                .isEqualTo(1.0);
        }

        @Test
        void should_incrementAbandoned_when_abandonedObserved() {
            metrics.observe(new TimeoutCounters.Abandoned("myTimeout", TimeoutCounters.AbandonedOutcome.SUCCEEDED));

            assertThat(meter.counterTotal(MetricNames.TIMEOUT_ABANDONED,
                Attributes.of(KEY_NAME, "myTimeout", KEY_OUTCOME, "SUCCEEDED"))).isEqualTo(1.0);
        }
    }

    @Nested
    class CircuitBreakerCountersHandling {
        @Test
        void should_tagReason_when_transitionToOpen() {
            metrics.observe(new CircuitBreakerCounters.Transition(
                "myCb", CircuitBreakerSnapshot.Phase.OPEN, CircuitBreakerEvent.Reason.FAILURE_RATE_EXCEEDED));

            assertThat(meter.counterTotal(MetricNames.CIRCUIT_BREAKER_TRANSITIONS,
                Attributes.of(KEY_NAME, "myCb", KEY_TO, "OPEN", KEY_REASON, "FAILURE_RATE_EXCEEDED"))).isEqualTo(1.0);
        }

        @Test
        void should_omitReasonTag_when_transitionToClosed() {
            metrics.observe(new CircuitBreakerCounters.Transition("myCb", CircuitBreakerSnapshot.Phase.CLOSED, null));

            assertThat(meter.counterTotal(MetricNames.CIRCUIT_BREAKER_TRANSITIONS,
                Attributes.of(KEY_NAME, "myCb", KEY_TO, "CLOSED"))).isEqualTo(1.0);
        }

        @Test
        void should_incrementByTestCalls_when_closedFromHalfOpenObserved() {
            metrics.observe(new CircuitBreakerCounters.ClosedFromHalfOpen("myCb", 5));

            assertThat(meter.counterTotal(MetricNames.CIRCUIT_BREAKER_CLOSED_TEST_CALLS,
                Attributes.of(KEY_NAME, "myCb"))).isEqualTo(5.0);
        }

        @Test
        void should_incrementByZero_when_closedFromHalfOpenObserved_with_noSuccessfulTestCalls() {
            metrics.observe(new CircuitBreakerCounters.ClosedFromHalfOpen("myCb", 0));

            assertThat(meter.counterTotal(MetricNames.CIRCUIT_BREAKER_CLOSED_TEST_CALLS,
                Attributes.of(KEY_NAME, "myCb"))).isEqualTo(0.0);
        }

        @Test
        void should_recordSafeDurationPair_when_callRecordedObserved_and_defaultMode() {
            metrics.observe(new CircuitBreakerCounters.CallRecorded("myCb", true, Duration.ofMillis(20)));

            var attributes = Attributes.of(KEY_NAME, "myCb", KEY_SUCCESSFUL, "true");
            assertThat(meter.counterTotal(MetricNames.CIRCUIT_BREAKER_CALLS_COUNT, attributes)).isEqualTo(1.0);
            assertThat(meter.doubleCounterTotal(MetricNames.CIRCUIT_BREAKER_CALLS_SUM, attributes)).isEqualTo(20.0);
        }

        @Test
        void should_recordHistogram_when_callRecordedObserved_and_detailedMode() {
            metrics = new OpenTelemetryResilienceMetrics(meter, DurationInstrumentationMode.DETAILED);

            metrics.observe(new CircuitBreakerCounters.CallRecorded("myCb", false, Duration.ofMillis(20)));

            assertThat(meter.lastHistogram(MetricNames.CIRCUIT_BREAKER_CALLS,
                Attributes.of(KEY_NAME, "myCb", KEY_SUCCESSFUL, "false"))).isEqualTo(20.0);
        }

        @Test
        void should_tagPhase_when_rejectedObserved() {
            metrics.observe(new CircuitBreakerCounters.Rejected("myCb", CircuitBreakerEvent.RejectingPhase.HALF_OPEN));

            assertThat(meter.counterTotal(MetricNames.CIRCUIT_BREAKER_REJECTED,
                Attributes.of(KEY_NAME, "myCb", KEY_PHASE, "HALF_OPEN"))).isEqualTo(1.0);
        }
    }

    @Nested
    class BulkheadCountersHandling {
        @Test
        void should_tagOutcome_when_callObserved() {
            metrics.observe(new BulkheadCounters.Call("myBulkhead", BulkheadCounters.Outcome.PERMITTED));

            assertThat(meter.counterTotal(MetricNames.BULKHEAD_CALLS,
                Attributes.of(KEY_NAME, "myBulkhead", KEY_OUTCOME, "PERMITTED"))).isEqualTo(1.0);
        }
    }

    @Nested
    class RateLimiterCountersHandling {
        @Test
        void should_tagOutcome_when_callObserved() {
            metrics.observe(new RateLimiterCounters.Call("myLimiter", RateLimiterCounters.Outcome.REJECTED));

            assertThat(meter.counterTotal(MetricNames.RATE_LIMITER_CALLS,
                Attributes.of(KEY_NAME, "myLimiter", KEY_OUTCOME, "REJECTED"))).isEqualTo(1.0);
        }
    }

    @Nested
    class PolicyCountersHandling {
        @Test
        void should_tagOuterAndInner_when_validationWarningObserved() {
            metrics.observe(new PolicyCounters.ValidationWarning(PatternKind.RETRY, PatternKind.CIRCUIT_BREAKER));

            assertThat(meter.counterTotal(MetricNames.POLICY_VALIDATION_WARNINGS,
                Attributes.of(KEY_OUTER, "RETRY", KEY_INNER, "CIRCUIT_BREAKER"))).isEqualTo(1.0);
        }
    }

    @Nested
    class SnapshotHandling {
        @Test
        void should_exposePhaseAsOrdinal_when_stateObserved() {
            metrics.observe(new CircuitBreakerSnapshot.State("myCb", CircuitBreakerSnapshot.Phase.HALF_OPEN));

            assertThat(meter.lastGauge(MetricNames.CIRCUIT_BREAKER_STATE, Attributes.of(KEY_NAME, "myCb")))
                .isEqualTo(2.0);
        }

        @Test
        void should_overwritePreviousValue_when_stateObservedTwice() {
            metrics.observe(new CircuitBreakerSnapshot.State("myCb", CircuitBreakerSnapshot.Phase.CLOSED));
            metrics.observe(new CircuitBreakerSnapshot.State("myCb", CircuitBreakerSnapshot.Phase.OPEN));

            assertThat(meter.lastGauge(MetricNames.CIRCUIT_BREAKER_STATE, Attributes.of(KEY_NAME, "myCb")))
                .isEqualTo(1.0);
        }

        @Test
        void should_mirrorFailureRate_when_failureRateObserved() {
            metrics.observe(new CircuitBreakerSnapshot.FailureRate("myCb", 0.42));

            assertThat(meter.lastGauge(MetricNames.CIRCUIT_BREAKER_FAILURE_RATE, Attributes.of(KEY_NAME, "myCb")))
                .isEqualTo(0.42);
        }

        @Test
        void should_mirrorActiveCalls_when_activeCallsObserved() {
            metrics.observe(new BulkheadSnapshot.ActiveCalls("myBulkhead", 3));

            assertThat(meter.lastGauge(MetricNames.BULKHEAD_ACTIVE_CALLS, Attributes.of(KEY_NAME, "myBulkhead")))
                .isEqualTo(3.0);
        }

        @Test
        void should_mirrorRemainingPermits_when_remainingPermitsObserved() {
            metrics.observe(new RateLimiterSnapshot.RemainingPermits("myLimiter", 7));

            assertThat(meter.lastGauge(MetricNames.RATE_LIMITER_REMAINING_PERMITS, Attributes.of(KEY_NAME, "myLimiter")))
                .isEqualTo(7.0);
        }
    }

    @Nested
    class MultiInstanceIsolation {
        @Test
        void should_keepStateIndependent_when_twoCircuitBreakersObserved() {
            metrics.observe(new CircuitBreakerSnapshot.State("cbA", CircuitBreakerSnapshot.Phase.OPEN));
            metrics.observe(new CircuitBreakerSnapshot.State("cbB", CircuitBreakerSnapshot.Phase.CLOSED));

            assertThat(meter.lastGauge(MetricNames.CIRCUIT_BREAKER_STATE, Attributes.of(KEY_NAME, "cbA")))
                .isEqualTo(1.0);
            assertThat(meter.lastGauge(MetricNames.CIRCUIT_BREAKER_STATE, Attributes.of(KEY_NAME, "cbB")))
                .isEqualTo(0.0);
        }

        @Test
        void should_keepFailureRateIndependent_when_twoCircuitBreakersObserved() {
            metrics.observe(new CircuitBreakerSnapshot.FailureRate("cbA", 0.75));
            metrics.observe(new CircuitBreakerSnapshot.FailureRate("cbB", 0.10));

            assertThat(meter.lastGauge(MetricNames.CIRCUIT_BREAKER_FAILURE_RATE, Attributes.of(KEY_NAME, "cbA")))
                .isEqualTo(0.75);
            assertThat(meter.lastGauge(MetricNames.CIRCUIT_BREAKER_FAILURE_RATE, Attributes.of(KEY_NAME, "cbB")))
                .isEqualTo(0.10);
        }

        @Test
        void should_keepActiveCallsIndependent_when_twoBulkheadsObserved() {
            metrics.observe(new BulkheadSnapshot.ActiveCalls("bulkheadA", 3));
            metrics.observe(new BulkheadSnapshot.ActiveCalls("bulkheadB", 9));

            assertThat(meter.lastGauge(MetricNames.BULKHEAD_ACTIVE_CALLS, Attributes.of(KEY_NAME, "bulkheadA")))
                .isEqualTo(3.0);
            assertThat(meter.lastGauge(MetricNames.BULKHEAD_ACTIVE_CALLS, Attributes.of(KEY_NAME, "bulkheadB")))
                .isEqualTo(9.0);
        }

        @Test
        void should_keepRemainingPermitsIndependent_when_twoRateLimitersObserved() {
            metrics.observe(new RateLimiterSnapshot.RemainingPermits("limiterA", 2));
            metrics.observe(new RateLimiterSnapshot.RemainingPermits("limiterB", 8));

            assertThat(meter.lastGauge(MetricNames.RATE_LIMITER_REMAINING_PERMITS, Attributes.of(KEY_NAME, "limiterA")))
                .isEqualTo(2.0);
            assertThat(meter.lastGauge(MetricNames.RATE_LIMITER_REMAINING_PERMITS, Attributes.of(KEY_NAME, "limiterB")))
                .isEqualTo(8.0);
        }
    }
}
