package io.github.teceli.resiliencia.opentelemetry;

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
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ResilienceMetrics} backed by an OpenTelemetry {@link Meter}.
 *
 * <p>Duration metrics ({@code resilience.timeout.duration}, {@code resilience.circuitbreaker.calls})
 * are recorded according to the configured {@link DurationInstrumentationMode} — {@code SAFE}
 * (default, lock-free counter pair) or {@code DETAILED} (opt-in, {@code DoubleHistogram}, see that
 * enum's Javadoc for the pinning risk it accepts).
 *
 * <p>Every metric emitted here is tagged with the pattern instance's {@code name} (the
 * {@code "name"} attribute key). That name must be a static, compile-time-known string — never
 * request-derived or tenant-derived — since it becomes a cardinality-bounded attribute value in
 * the OpenTelemetry SDK; unbounded distinct names would cause unbounded memory growth in the
 * metrics backend.
 */
public final class OpenTelemetryResilienceMetrics implements ResilienceMetrics {
    private static final AttributeKey<String> KEY_NAME = AttributeKey.stringKey("name");
    private static final AttributeKey<String> KEY_CAUSE = AttributeKey.stringKey("cause");
    private static final AttributeKey<String> KEY_OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> KEY_TO = AttributeKey.stringKey("to");
    private static final AttributeKey<String> KEY_REASON = AttributeKey.stringKey("reason");
    private static final AttributeKey<String> KEY_PHASE = AttributeKey.stringKey("phase");
    private static final AttributeKey<String> KEY_SUCCESSFUL = AttributeKey.stringKey("successful");
    private static final AttributeKey<String> KEY_OUTER = AttributeKey.stringKey("outer");
    private static final AttributeKey<String> KEY_INNER = AttributeKey.stringKey("inner");

    private final Meter meter;
    private final DurationInstrumentationMode durationMode;

    private final Map<String, LongCounter> longCounters = new ConcurrentHashMap<>();
    private final Map<String, DoubleCounter> doubleCounters = new ConcurrentHashMap<>();
    private final Map<String, DoubleHistogram> histograms = new ConcurrentHashMap<>();
    private final Map<String, DoubleGauge> gauges = new ConcurrentHashMap<>();

    public OpenTelemetryResilienceMetrics(Meter meter) {
        this(meter, DurationInstrumentationMode.SAFE);
    }

    public OpenTelemetryResilienceMetrics(Meter meter, DurationInstrumentationMode durationMode) {
        this.meter = meter;
        this.durationMode = durationMode;
    }

    @Override
    public void observe(Snapshot snapshot) {
        switch (snapshot) {
            case CircuitBreakerSnapshot.State(String name, CircuitBreakerSnapshot.Phase phase) ->
                setGauge(MetricNames.CIRCUIT_BREAKER_STATE, phase.ordinal(), KEY_NAME, name);
            case CircuitBreakerSnapshot.FailureRate(String name, double rate) ->
                setGauge(MetricNames.CIRCUIT_BREAKER_FAILURE_RATE, rate, KEY_NAME, name);
            case BulkheadSnapshot.ActiveCalls(String name, int count) ->
                setGauge(MetricNames.BULKHEAD_ACTIVE_CALLS, count, KEY_NAME, name);
            case RateLimiterSnapshot.RemainingPermits(String name, int remaining) ->
                setGauge(MetricNames.RATE_LIMITER_REMAINING_PERMITS, remaining, KEY_NAME, name);
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
                observeDuration(MetricNames.TIMEOUT_DURATION, MetricNames.TIMEOUT_DURATION_COUNT,
                    MetricNames.TIMEOUT_DURATION_SUM, elapsed, Attributes.of(KEY_NAME, name));
            case TimeoutCounters.Failed(String name, String cause) ->
                increment(MetricNames.TIMEOUT_FAILED, name, cause);
            case TimeoutCounters.TimedOut(String name) ->
                increment(MetricNames.TIMEOUT_TIMED_OUT, name);
            case TimeoutCounters.Abandoned(String name, TimeoutCounters.AbandonedOutcome outcome) ->
                increment(MetricNames.TIMEOUT_ABANDONED, Attributes.of(KEY_NAME, name, KEY_OUTCOME, outcome.name()));
        }
    }

    private void handleCircuitBreaker(CircuitBreakerCounters counters) {
        switch (counters) {
            case CircuitBreakerCounters.Transition(String name,
                                                   CircuitBreakerSnapshot.Phase to,
                                                   CircuitBreakerEvent.Reason reason) -> {
                var builder = Attributes.builder().put(KEY_NAME, name).put(KEY_TO, to.name());
                if (reason != null) {
                    builder.put(KEY_REASON, reason.name());
                }
                counter(MetricNames.CIRCUIT_BREAKER_TRANSITIONS).add(1, builder.build());
            }
            case CircuitBreakerCounters.ClosedFromHalfOpen(String name, int successfulTestCalls) ->
                counter(MetricNames.CIRCUIT_BREAKER_CLOSED_TEST_CALLS)
                    .add(successfulTestCalls, Attributes.of(KEY_NAME, name));
            case CircuitBreakerCounters.CallRecorded(String name, boolean successful, Duration elapsed) ->
                observeDuration(MetricNames.CIRCUIT_BREAKER_CALLS, MetricNames.CIRCUIT_BREAKER_CALLS_COUNT,
                    MetricNames.CIRCUIT_BREAKER_CALLS_SUM, elapsed,
                    Attributes.of(KEY_NAME, name, KEY_SUCCESSFUL, String.valueOf(successful)));
            case CircuitBreakerCounters.Rejected(String name, CircuitBreakerEvent.RejectingPhase phase) ->
                increment(MetricNames.CIRCUIT_BREAKER_REJECTED, Attributes.of(KEY_NAME, name, KEY_PHASE, phase.name()));
        }
    }

    private void handleBulkhead(BulkheadCounters counters) {
        switch (counters) {
            case BulkheadCounters.Call(String name, BulkheadCounters.Outcome outcome) ->
                increment(MetricNames.BULKHEAD_CALLS, Attributes.of(KEY_NAME, name, KEY_OUTCOME, outcome.name()));
        }
    }

    private void handleRateLimiter(RateLimiterCounters counters) {
        switch (counters) {
            case RateLimiterCounters.Call(String name, RateLimiterCounters.Outcome outcome) ->
                increment(MetricNames.RATE_LIMITER_CALLS, Attributes.of(KEY_NAME, name, KEY_OUTCOME, outcome.name()));
        }
    }

    private void handlePolicy(PolicyCounters counters) {
        switch (counters) {
            case PolicyCounters.ValidationWarning(PatternKind outer, PatternKind inner) ->
                counter(MetricNames.POLICY_VALIDATION_WARNINGS)
                    .add(1, Attributes.of(KEY_OUTER, outer.name(), KEY_INNER, inner.name()));
        }
    }

    private void increment(String metricName, String name) {
        counter(metricName).add(1, Attributes.of(KEY_NAME, name));
    }

    private void increment(String metricName, String name, String cause) {
        var attributes = cause != null
            ? Attributes.of(KEY_NAME, name, KEY_CAUSE, cause)
            : Attributes.of(KEY_NAME, name);
        counter(metricName).add(1, attributes);
    }

    private void increment(String metricName, Attributes attributes) {
        counter(metricName).add(1, attributes);
    }

    private void observeDuration(String histogramName, String countName, String sumName,
                                  Duration elapsed, Attributes attributes) {
        var millis = elapsed.toNanos() / 1_000_000.0;
        if (durationMode == DurationInstrumentationMode.DETAILED) {
            histogram(histogramName).record(millis, attributes);
        } else {
            counter(countName).add(1, attributes);
            doubleCounter(sumName).add(millis, attributes);
        }
    }

    private void setGauge(String metricName, double value, AttributeKey<String> key, String name) {
        gauges.computeIfAbsent(metricName, n -> meter.gaugeBuilder(n).build()).set(value, Attributes.of(key, name));
    }

    private LongCounter counter(String metricName) {
        return longCounters.computeIfAbsent(metricName, name -> meter.counterBuilder(name).build());
    }

    private DoubleCounter doubleCounter(String metricName) {
        return doubleCounters.computeIfAbsent(metricName,
            name -> meter.counterBuilder(name).ofDoubles().setUnit("ms").build());
    }

    private DoubleHistogram histogram(String metricName) {
        return histograms.computeIfAbsent(metricName,
            name -> meter.histogramBuilder(name).setUnit("ms").build());
    }
}
