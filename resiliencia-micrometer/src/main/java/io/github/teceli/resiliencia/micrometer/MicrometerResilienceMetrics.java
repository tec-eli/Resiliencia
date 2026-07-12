package io.github.teceli.resiliencia.micrometer;

import io.github.teceli.resiliencia.metrics.Counters;
import io.github.teceli.resiliencia.metrics.ResilienceMetrics;
import io.github.teceli.resiliencia.metrics.Snapshot;
import io.github.teceli.resiliencia.metrics.bulkhead.BulkheadCounters;
import io.github.teceli.resiliencia.metrics.circuitbreaker.CircuitBreakerCounters;
import io.github.teceli.resiliencia.metrics.policy.PolicyCounters;
import io.github.teceli.resiliencia.metrics.ratelimiter.RateLimiterCounters;
import io.github.teceli.resiliencia.metrics.retry.RetryCounters;
import io.github.teceli.resiliencia.metrics.timeout.TimeoutCounters;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.time.Duration;

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

    public MicrometerResilienceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void observe(Snapshot snapshot) {
        throw new UnsupportedOperationException("Gauges are implemented in a later step");
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
            case RetryCounters.Success(String name, int totalAttempts) ->
                increment(MetricNames.RETRY_SUCCESS, name);
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
        throw new UnsupportedOperationException("CircuitBreakerCounters are implemented in a later step");
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
                increment(MetricNames.RATELIMITER_CALLS, name, TAG_OUTCOME, outcome.name());
        }
    }

    private void handlePolicy(PolicyCounters counters) {
        switch (counters) {
            case PolicyCounters.ValidationWarning(var outer, var inner) ->
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
}
