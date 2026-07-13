package io.github.teceli.resiliencia.micrometer;

import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.metrics.Counters;
import io.github.teceli.resiliencia.metrics.ResilienceMetrics;
import io.github.teceli.resiliencia.metrics.Snapshot;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ResilienceMetrics} backed by a Micrometer {@link MeterRegistry}.
 *
 * <p>Timers created by this class never opt into {@code publishPercentileHistogram()} or
 * {@code serviceLevelObjectives(...)}, to avoid the internal {@code synchronized} bucket-rotation
 * path those features enable.
 */
public final class MicrometerResilienceMetrics implements ResilienceMetrics {
    private static final String TAG_NAME = "name";
    private static final String TAG_CAUSE = "cause";
    private static final String TAG_OUTCOME = "outcome";

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> gaugeHolders = new ConcurrentHashMap<>();

    public MicrometerResilienceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void observe(Snapshot snapshot) {
        switch (snapshot) {
            case CircuitBreakerSnapshot.State(String name, CircuitBreakerSnapshot.Phase phase) ->
                setGauge(MetricNames.CIRCUIT_BREAKER_STATE, name, phase.ordinal());
            case CircuitBreakerSnapshot.FailureRate(String name, double rate) ->
                setGauge(MetricNames.CIRCUIT_BREAKER_FAILURE_RATE, name, rate);
            case BulkheadSnapshot.ActiveCalls(String name, int count) ->
                setGauge(MetricNames.BULKHEAD_ACTIVE_CALLS, name, count);
            case RateLimiterSnapshot.RemainingPermits(String name, int remaining) ->
                setGauge(MetricNames.RATE_LIMITER_REMAINING_PERMITS, name, remaining);
        }
    }

    @Override
    public void observe(Counters counters) {
        switch (counters) {
            case RetryCounters c -> handleRetry(c);
            case TimeoutCounters c -> handleTimeout(c);
            case CircuitBreakerCounters c -> handleCircuitBreaker(c);
            case BulkheadCounters c -> handleBulkhead(c);
            case RateLimiterCounters c -> handleRateLimiter(c);
            case PolicyCounters c -> handlePolicy(c);
        }
    }

    private void handleRetry(RetryCounters counters) {
        switch (counters) {
            case RetryCounters.AttemptFailed(String name, String cause) ->
                increment(MetricNames.RETRY_ATTEMPTS, name, cause);
            case RetryCounters.Success c ->
                increment(MetricNames.RETRY_SUCCESS, c.name());
            case RetryCounters.Exhausted(String name, String cause) ->
                increment(MetricNames.RETRY_EXHAUSTED, name, cause);
            case RetryCounters.Rejected(String name, String cause) ->
                increment(MetricNames.RETRY_REJECTED, name, cause);
            case RetryCounters.Interrupted(String name, String cause) ->
                increment(MetricNames.RETRY_INTERRUPTED, name, cause);
        }
    }

    private void handleTimeout(TimeoutCounters counters) {
        switch (counters) {
            case TimeoutCounters.Succeeded(String name, Duration elapsed) ->
                registry.timer(MetricNames.TIMEOUT_DURATION, Tags.of(TAG_NAME, name)).record(elapsed);
            case TimeoutCounters.Failed(String name, String cause) ->
                increment(MetricNames.TIMEOUT_FAILED, name, cause);
            case TimeoutCounters.TimedOut(String name) ->
                increment(MetricNames.TIMEOUT_TIMED_OUT, name);
            case TimeoutCounters.Abandoned(String name, TimeoutCounters.AbandonedOutcome outcome) ->
                increment(MetricNames.TIMEOUT_ABANDONED, name, TAG_OUTCOME, outcome.name());
        }
    }

    private void handleCircuitBreaker(CircuitBreakerCounters counters) {
        switch (counters) {
            case CircuitBreakerCounters.Transition(String name,
                                                   CircuitBreakerSnapshot.Phase to,
                                                   CircuitBreakerEvent.Reason reason) -> {
                var tags = Tags.of(TAG_NAME, name, "to", to.name());
                if (reason != null) {
                    tags = tags.and("reason", reason.name());
                }
                registry.counter(MetricNames.CIRCUIT_BREAKER_TRANSITIONS, tags).increment();
            }
            case CircuitBreakerCounters.ClosedFromHalfOpen(String name, int successfulTestCalls) ->
                registry.counter(MetricNames.CIRCUIT_BREAKER_CLOSED_TEST_CALLS, Tags.of(TAG_NAME, name))
                    .increment(successfulTestCalls);
            case CircuitBreakerCounters.CallRecorded(String name, boolean successful, Duration elapsed) ->
                registry.timer(MetricNames.CIRCUIT_BREAKER_CALLS,
                    Tags.of(TAG_NAME, name, "successful", String.valueOf(successful))).record(elapsed);
            case CircuitBreakerCounters.Rejected(String name, CircuitBreakerEvent.RejectingPhase phase) ->
                increment(MetricNames.CIRCUIT_BREAKER_REJECTED, name, "phase", phase.name());
        }
    }

    private void handleBulkhead(BulkheadCounters counters) {
        switch (counters) {
            case BulkheadCounters.Call(String name, BulkheadCounters.Outcome outcome) ->
                increment(MetricNames.BULKHEAD_CALLS, name, TAG_OUTCOME, outcome.name());
        }
    }

    private void handleRateLimiter(RateLimiterCounters counters) {
        switch (counters) {
            case RateLimiterCounters.Call(String name, RateLimiterCounters.Outcome outcome) ->
                increment(MetricNames.RATE_LIMITER_CALLS, name, TAG_OUTCOME, outcome.name());
        }
    }

    private void handlePolicy(PolicyCounters counters) {
        switch (counters) {
            case PolicyCounters.ValidationWarning(PatternKind outer, PatternKind inner) ->
                registry.counter(MetricNames.POLICY_VALIDATION_WARNINGS,
                    Tags.of("outer", outer.name(), "inner", inner.name())).increment();
        }
    }

    private void increment(String metricName, String name) {
        registry.counter(metricName, Tags.of(TAG_NAME, name)).increment();
    }

    private void increment(String metricName, String name, String cause) {
        increment(metricName, name, TAG_CAUSE, cause);
    }

    private void increment(String metricName, String name, String extraTagKey, String extraTagValue) {
        var tags = Tags.of(TAG_NAME, name);
        if (extraTagValue != null) {
            tags = tags.and(extraTagKey, extraTagValue);
        }
        registry.counter(metricName, tags).increment();
    }

    private void setGauge(String metricName, String name, double value) {
        var holder = gaugeHolders.computeIfAbsent(metricName + "|" + name, key -> {
            var reference = new AtomicLong(Double.doubleToLongBits(value));
            registry.gauge(metricName, Tags.of(TAG_NAME, name), reference,
                ref -> Double.longBitsToDouble(ref.get()));
            return reference;
        });
        holder.set(Double.doubleToLongBits(value));
    }
}
