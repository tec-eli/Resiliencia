package io.github.teceli.resiliencia.metrics;

import io.github.teceli.resiliencia.compose.PolicyValidationWarning;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.spi.ResilienceEvent;
import io.github.teceli.resiliencia.metrics.bulkhead.BulkheadCounters;
import io.github.teceli.resiliencia.metrics.bulkhead.BulkheadSnapshot;
import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerCounters;
import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerSnapshot;
import io.github.teceli.resiliencia.metrics.policy.PolicyCounters;
import io.github.teceli.resiliencia.metrics.ratelimiter.RateLimiterCounters;
import io.github.teceli.resiliencia.metrics.ratelimiter.RateLimiterSnapshot;
import io.github.teceli.resiliencia.metrics.retry.RetryCounters;
import io.github.teceli.resiliencia.metrics.timeout.TimeoutCounters;
import io.github.teceli.resiliencia.patterns.bulkhead.BulkheadEvent;
import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreakerEvent;
import io.github.teceli.resiliencia.patterns.ratelimiter.RateLimiterEvent;
import io.github.teceli.resiliencia.patterns.retry.RetryEvent;
import io.github.teceli.resiliencia.patterns.timeout.TimeoutEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ResilienceMetricsListenerTest {

    @Mock
    private ResilienceMetrics metrics;

    private ResilienceMetricsListener listener;

    @BeforeEach
    void setUp() {
        listener = new ResilienceMetricsListener(metrics);
    }

    @Nested
    class RetryEventHandling {
        @Test
        void should_recordAttemptFailed_when_retryEventAttemptFailedEmitted() {
            var error = new RuntimeException("test error");
            var event = new RetryEvent.AttemptFailed(Instant.now(), "myRetry", 1, error);

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(RetryCounters.AttemptFailed.class));
        }

        @Test
        void should_recordSuccess_when_retryEventSuccessEmitted() {
            var event = new RetryEvent.Success(Instant.now(), "myRetry", 3);

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(RetryCounters.Success.class));
        }

        @Test
        void should_recordExhausted_when_retryEventExhaustedEmitted() {
            var error = new RuntimeException("exhausted");
            var event = new RetryEvent.Exhausted(Instant.now(), "myRetry", 3, error);

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(RetryCounters.Exhausted.class));
        }

        @Test
        void should_recordRejected_when_retryEventRejectedEmitted() {
            var error = new RuntimeException("rejected");
            var event = new RetryEvent.Rejected(Instant.now(), "myRetry", 1, error);

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(RetryCounters.Rejected.class));
        }

        @Test
        void should_recordInterrupted_when_retryEventInterruptedEmitted() {
            var error = new RuntimeException("interrupted");
            var event = new RetryEvent.Interrupted(Instant.now(), "myRetry", 2, error);

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(RetryCounters.Interrupted.class));
        }
    }

    @Nested
    class TimeoutEventHandling {
        @Test
        void should_recordSucceeded_when_timeoutEventSucceededEmitted() {
            var event = new TimeoutEvent.Succeeded(Instant.now(), "myTimeout",
                Duration.ofMillis(100));

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(TimeoutCounters.Succeeded.class));
        }

        @Test
        void should_recordFailed_when_timeoutEventFailedEmitted() {
            var error = new RuntimeException("failed");
            var event = new TimeoutEvent.Failed(Instant.now(), "myTimeout", error);

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(TimeoutCounters.Failed.class));
        }

        @Test
        void should_recordTimedOut_when_timeoutEventTimedOutEmitted() {
            var event = new TimeoutEvent.TimedOut(Instant.now(), "myTimeout",
                Duration.ofSeconds(5));

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(TimeoutCounters.TimedOut.class));
        }

        @Test
        void should_recordAbandoned_when_abandonedWorkerSucceededEmitted() {
            var event = new TimeoutEvent.AbandonedWorkerSucceeded(Instant.now(), "myTimeout");

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(TimeoutCounters.Abandoned.class));
        }

        @Test
        void should_recordAbandoned_when_abandonedWorkerFailedEmitted() {
            var error = new RuntimeException("abandoned");
            var event = new TimeoutEvent.AbandonedWorkerFailed(Instant.now(), "myTimeout", error);

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(TimeoutCounters.Abandoned.class));
        }
    }

    @Nested
    class CircuitBreakerEventHandling {
        @Test
        void should_recordCallRecordedAndFailureRate_when_callRecordedEmitted() {
            var event = new CircuitBreakerEvent.CallRecorded(Instant.now(), "myCB", true,
                Duration.ofMillis(50), 0.1);

            listener.onEvent(event);

            var order = inOrder(metrics);
            order.verify(metrics).observe(any(CircuitBreakerCounters.CallRecorded.class));
            order.verify(metrics).observe(any(CircuitBreakerSnapshot.FailureRate.class));
            order.verifyNoMoreInteractions();
        }

        @Test
        void should_recordTransitionToOpenAndState_when_openedEventEmitted() {
            var event = new CircuitBreakerEvent.Opened(Instant.now(), "myCB",
                CircuitBreakerEvent.Reason.FAILURE_RATE_EXCEEDED);

            listener.onEvent(event);

            var order = inOrder(metrics);
            order.verify(metrics).observe(any(CircuitBreakerCounters.Transition.class));
            order.verify(metrics).observe(any(CircuitBreakerSnapshot.State.class));
            order.verifyNoMoreInteractions();
        }

        @Test
        void should_recordTransitionToHalfOpenAndState_when_halfOpenedEventEmitted() {
            var event = new CircuitBreakerEvent.HalfOpened(Instant.now(), "myCB");

            listener.onEvent(event);

            var order = inOrder(metrics);
            order.verify(metrics).observe(any(CircuitBreakerCounters.Transition.class));
            order.verify(metrics).observe(any(CircuitBreakerSnapshot.State.class));
            order.verifyNoMoreInteractions();
        }

        @Test
        void should_recordTransitionToClosedAndClosedFromHalfOpenAndState_when_closedEventEmitted() {
            var event = new CircuitBreakerEvent.Closed(Instant.now(), "myCB", 5);

            listener.onEvent(event);

            var order = inOrder(metrics);
            order.verify(metrics).observe(any(CircuitBreakerCounters.Transition.class));
            order.verify(metrics).observe(any(CircuitBreakerCounters.ClosedFromHalfOpen.class));
            order.verify(metrics).observe(any(CircuitBreakerSnapshot.State.class));
            order.verifyNoMoreInteractions();
        }

        @Test
        void should_recordRejected_when_rejectedEventEmitted() {
            var event = new CircuitBreakerEvent.Rejected(Instant.now(), "myCB",
                CircuitBreakerEvent.RejectingPhase.OPEN);

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(CircuitBreakerCounters.Rejected.class));
        }
    }

    @Nested
    class BulkheadEventHandling {
        @Test
        void should_recordPermittedAndActiveCalls_when_permittedEventEmitted() {
            var event = new BulkheadEvent.Permitted(Instant.now(), "myBulk", 5);

            listener.onEvent(event);

            var order = inOrder(metrics);
            order.verify(metrics).observe(any(BulkheadCounters.Call.class));
            order.verify(metrics).observe(any(BulkheadSnapshot.ActiveCalls.class));
            order.verifyNoMoreInteractions();
        }

        @Test
        void should_recordRejected_when_rejectedEventEmitted() {
            var event = new BulkheadEvent.Rejected(Instant.now(), "myBulk", 10,
                Duration.ofMillis(100));

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(BulkheadCounters.Call.class));
        }

        @Test
        void should_recordActiveCalls_when_finishedEventEmitted() {
            var event = new BulkheadEvent.Finished(Instant.now(), "myBulk", 4);

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(BulkheadSnapshot.ActiveCalls.class));
        }
    }

    @Nested
    class RateLimiterEventHandling {
        @Test
        void should_recordPermittedAndRemainingPermits_when_permittedEventEmitted() {
            var event = new RateLimiterEvent.Permitted(Instant.now(), "myRL", 98);

            listener.onEvent(event);

            var order = inOrder(metrics);
            order.verify(metrics).observe(any(RateLimiterCounters.Call.class));
            order.verify(metrics).observe(any(RateLimiterSnapshot.RemainingPermits.class));
            order.verifyNoMoreInteractions();
        }

        @Test
        void should_recordRejected_when_rejectedEventEmitted() {
            var event = new RateLimiterEvent.Rejected(Instant.now(), "myRL",
                Duration.ofMillis(50));

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(RateLimiterCounters.Call.class));
        }
    }

    @Nested
    class PolicyEventHandling {
        @Test
        void should_recordValidationWarning_when_policyValidationWarningEmitted() {
            var event = new PolicyValidationWarning(Instant.now(), PatternKind.RETRY,
                PatternKind.CIRCUIT_BREAKER, "problem", "fix");

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(PolicyCounters.ValidationWarning.class));
        }
    }

    @Nested
    class ExceptionIsolation {
        @Test
        void should_continueProcessing_when_recordSnapshotThrows() {
            doThrow(new RuntimeException("Backend failure")).when(metrics)
                .observe(any(Snapshot.class));
            var callRecordedEvent = new CircuitBreakerEvent.CallRecorded(Instant.now(), "myCB",
                true, Duration.ofMillis(50), 0.1);

            listener.onEvent(callRecordedEvent);

            verify(metrics, times(1)).observe(any(CircuitBreakerCounters.CallRecorded.class));
            verify(metrics, times(1)).observe(any(CircuitBreakerSnapshot.FailureRate.class));
        }

        @Test
        void should_continueProcessing_when_recordCountersThrows() {
            doThrow(new RuntimeException("Backend failure")).when(metrics)
                .observe(any(Counters.class));
            var event = new RetryEvent.AttemptFailed(Instant.now(), "myRetry", 1,
                new RuntimeException("error"));

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(Counters.class));
        }

        @Test
        void should_isolateMultipleFailures_when_eventDrivesMultipleRecords() {
            doThrow(new RuntimeException("First failure")).when(metrics)
                .observe(any(CircuitBreakerCounters.CallRecorded.class));
            doThrow(new RuntimeException("Second failure")).when(metrics)
                .observe(any(CircuitBreakerSnapshot.FailureRate.class));
            var event = new CircuitBreakerEvent.CallRecorded(Instant.now(), "myCB", true,
                Duration.ofMillis(50), 0.1);

            listener.onEvent(event);

            verify(metrics, times(1)).observe(any(CircuitBreakerCounters.CallRecorded.class));
            verify(metrics, times(1)).observe(any(CircuitBreakerSnapshot.FailureRate.class));
        }
    }

    @Nested
    class CardinalityControl {
        @Test
        void should_useSimpleClassName_when_exceptionTypeInAllowlist() {
            var allowlist = Set.<Class<? extends Throwable>>of(RuntimeException.class);
            listener = new ResilienceMetricsListener(metrics, allowlist);
            var error = new RuntimeException("test");
            var event = new RetryEvent.AttemptFailed(Instant.now(), "myRetry", 1, error);

            listener.onEvent(event);

            var captor = org.mockito.ArgumentCaptor.forClass(RetryCounters.class);
            verify(metrics).observe(captor.capture());
            assertThat(captor.getValue()).isInstanceOf(RetryCounters.AttemptFailed.class);
            var attemptFailed = (RetryCounters.AttemptFailed) captor.getValue();
            assertThat(attemptFailed.cause()).isEqualTo("RuntimeException");
        }

        @Test
        void should_useBucket_other_when_exceptionTypeNotInAllowlist() {
            var allowlist = Set.<Class<? extends Throwable>>of(IllegalStateException.class);
            listener = new ResilienceMetricsListener(metrics, allowlist);
            var error = new RuntimeException("test");
            var event = new RetryEvent.AttemptFailed(Instant.now(), "myRetry", 1, error);

            listener.onEvent(event);

            var captor = org.mockito.ArgumentCaptor.forClass(RetryCounters.class);
            verify(metrics).observe(captor.capture());
            assertThat(captor.getValue()).isInstanceOf(RetryCounters.AttemptFailed.class);
            var attemptFailed = (RetryCounters.AttemptFailed) captor.getValue();
            assertThat(attemptFailed.cause()).isEqualTo("other");
        }

        @Test
        void should_useCauseNull_when_allowlistEmpty() {
            listener = new ResilienceMetricsListener(metrics, Set.of());
            var error = new RuntimeException("test");
            var event = new RetryEvent.AttemptFailed(Instant.now(), "myRetry", 1, error);

            listener.onEvent(event);

            var captor = org.mockito.ArgumentCaptor.forClass(RetryCounters.class);
            verify(metrics).observe(captor.capture());
            assertThat(captor.getValue()).isInstanceOf(RetryCounters.AttemptFailed.class);
            var attemptFailed = (RetryCounters.AttemptFailed) captor.getValue();
            assertThat(attemptFailed.cause()).isNull();
        }

        @Test
        void should_boundCardinalityToAllowlistSize() {
            var allowlist = Set.<Class<? extends Throwable>>of(IllegalStateException.class,
                IllegalArgumentException.class);
            listener = new ResilienceMetricsListener(metrics, allowlist);

            var error1 = new IllegalStateException("err");
            var error2 = new IllegalArgumentException("err");
            var error3 = new RuntimeException("err");
            var error4 = new UnsupportedOperationException("err");

            listener.onEvent(new RetryEvent.AttemptFailed(Instant.now(), "r1", 1, error1));
            listener.onEvent(new RetryEvent.AttemptFailed(Instant.now(), "r2", 1, error2));
            listener.onEvent(new RetryEvent.AttemptFailed(Instant.now(), "r3", 1, error3));
            listener.onEvent(new RetryEvent.AttemptFailed(Instant.now(), "r4", 1, error4));

            verify(metrics, times(4)).observe(any(Counters.class));
        }

        @Test
        void should_useAllowlistedAncestorName_when_thrownExceptionIsSubtypeOfAllowlistedClass() {
            var allowlist = Set.<Class<? extends Throwable>>of(java.io.IOException.class);
            listener = new ResilienceMetricsListener(metrics, allowlist);
            var error = new java.io.FileNotFoundException("missing");
            var event = new RetryEvent.AttemptFailed(Instant.now(), "myRetry", 1, error);

            listener.onEvent(event);

            var captor = org.mockito.ArgumentCaptor.forClass(RetryCounters.class);
            verify(metrics).observe(captor.capture());
            var attemptFailed = (RetryCounters.AttemptFailed) captor.getValue();
            assertThat(attemptFailed.cause())
                .as("subtype matching should tag with the allowlisted ancestor's name, not the thrown "
                    + "exception's own runtime class name, so cardinality stays bounded by allowlist size")
                .isEqualTo("IOException");
        }

        @Test
        void should_useMostSpecificAllowlistedAncestor_when_thrownExceptionMatchesMultipleAllowlistedAncestors() {
            var allowlist = Set.<Class<? extends Throwable>>of(RuntimeException.class, IllegalStateException.class);
            listener = new ResilienceMetricsListener(metrics, allowlist);
            var error = new IllegalStateException("test");
            var event = new RetryEvent.AttemptFailed(Instant.now(), "myRetry", 1, error);

            listener.onEvent(event);

            var captor = org.mockito.ArgumentCaptor.forClass(RetryCounters.class);
            verify(metrics).observe(captor.capture());
            var attemptFailed = (RetryCounters.AttemptFailed) captor.getValue();
            assertThat(attemptFailed.cause())
                .as("the most specific matching allowlisted ancestor should be used, not the broader one")
                .isEqualTo("IllegalStateException");
        }

        @Test
        void should_useBucket_other_when_thrownExceptionIsSupertypeOfAllowlistedClass() {
            var allowlist = Set.<Class<? extends Throwable>>of(IllegalStateException.class);
            listener = new ResilienceMetricsListener(metrics, allowlist);
            var error = new RuntimeException("test");
            var event = new RetryEvent.AttemptFailed(Instant.now(), "myRetry", 1, error);

            listener.onEvent(event);

            var captor = org.mockito.ArgumentCaptor.forClass(RetryCounters.class);
            verify(metrics).observe(captor.capture());
            var attemptFailed = (RetryCounters.AttemptFailed) captor.getValue();
            assertThat(attemptFailed.cause())
                .as("a broader exception thrown where only a narrower subtype is allowlisted should not match")
                .isEqualTo("other");
        }
    }

    @Nested
    class ConstructorNullHandling {
        @Test
        void should_notThrow_when_metricsArgumentIsNull() {
            assertThatCode(() -> new ResilienceMetricsListener(null)).doesNotThrowAnyException();
        }

        @Test
        void should_swallowSilently_when_metricsIsNullAndEventEmitted() {
            var nullMetricsListener = new ResilienceMetricsListener(null);
            var event = new RetryEvent.AttemptFailed(Instant.now(), "myRetry", 1,
                new RuntimeException("boom"));

            assertThatCode(() -> nullMetricsListener.onEvent(event))
                .as("safeObserve() wraps every backend call in try/catch, so the NullPointerException from a null "
                    + "metrics reference is caught and logged, not propagated to the caller")
                .doesNotThrowAnyException();
        }

        @Test
        void should_throwNullPointerException_when_causeAllowlistIsNull() {
            assertThatThrownBy(() -> new ResilienceMetricsListener(metrics, null))
                .as("this NullPointerException originates from Set.copyOf(null) inside the constructor, not from "
                    + "an explicit Objects.requireNonNull guard")
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class NullNameHandling {
        @Test
        void should_forwardEventWithNullName_when_sourceEventHasNullName() {
            var event = new RetryEvent.AttemptFailed(Instant.now(), null, 1, new RuntimeException("boom"));

            listener.onEvent(event);

            var captor = org.mockito.ArgumentCaptor.forClass(RetryCounters.class);
            verify(metrics).observe(captor.capture());
            assertThat(captor.getValue()).isInstanceOf(RetryCounters.AttemptFailed.class);
            assertThat(((RetryCounters.AttemptFailed) captor.getValue()).name())
                .as("neither the source event nor the Counters record it maps to validates the name, so a null "
                    + "name flows through untouched instead of being rejected")
                .isNull();
        }
    }

    @Nested
    class NameCardinality {
        @Test
        void should_forwardEveryDistinctName_when_manyUniqueNamesObserved() {
            var uniqueNameCount = 500;
            for (var i = 0; i < uniqueNameCount; i++) {
                listener.onEvent(new RetryEvent.AttemptFailed(Instant.now(), "retry-" + i, 1,
                    new RuntimeException("boom")));
            }

            var captor = org.mockito.ArgumentCaptor.forClass(RetryCounters.class);
            verify(metrics, times(uniqueNameCount)).observe(captor.capture());
            var distinctNames = captor.getAllValues().stream()
                .map(counters -> ((RetryCounters.AttemptFailed) counters).name())
                .distinct()
                .count();
            assertThat(distinctNames)
                .as("unlike the cause tag, which is bounded by causeAllowlist (see CardinalityControl above), "
                    + "the listener applies no bound to the name dimension — every distinct name is forwarded as-is")
                .isEqualTo(uniqueNameCount);
        }
    }

    @Nested
    class UnknownEventHandling {
        @Test
        void should_ignoreUnknownEvent_when_customPatternImplementsResilienceEvent() {
            var unknownEvent = new ResilienceEvent() {
                @Override
                public java.time.Instant timestamp() {
                    return Instant.now();
                }

                @Override
                public String patternName() {
                    return "custom";
                }
            };

            listener.onEvent(unknownEvent);

            verify(metrics, never()).observe(any(Snapshot.class));
            verify(metrics, never()).observe(any(Counters.class));
        }
    }
}
