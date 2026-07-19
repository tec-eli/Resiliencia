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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ResilienceMetrics} backed by a Micrometer {@link MeterRegistry}.
 *
 * <p>Timers created by this class never opt into {@code publishPercentileHistogram()} or
 * {@code serviceLevelObjectives(...)}, to avoid the internal {@code synchronized} bucket-rotation
 * path those features enable.
 *
 * <p>Every metric emitted here is tagged with the pattern instance's {@code name} (the
 * {@code "name"} tag). That name must be a static, compile-time-known string — never
 * request-derived or tenant-derived — since it becomes a cardinality-bounded tag value in the
 * Micrometer registry; unbounded distinct names would cause unbounded memory growth in the
 * metrics backend.
 *
 * <p>The per-{@code metricName + name} gauge holder cache is bounded at
 * {@link #MAX_GAUGE_CACHE_ENTRIES} entries. A caller respecting the "names must be static"
 * contract above will only ever register a small, fixed number of distinct combinations, so this
 * bound is never reached in normal use. If it is reached — a caller not respecting that contract —
 * further distinct combinations are not registered as new gauges (already-registered gauges keep
 * updating normally) and a single WARN is logged, rather than growing the cache without limit.
 * Not evicting is deliberate: an LRU-style eviction would silently stop updating a gauge a caller
 * is still actively using, which is worse than refusing new, previously-unseen entries once the
 * bound is hit. The cache lookup itself stays lock-free (no {@code synchronized}), consistent with
 * the pinning-avoidance contract for this module's event-to-metric mapping code — the trade-off is
 * that the bound is enforced with a plain read-then-act check on the cache size, not a single
 * atomic reservation, so under many threads racing to register distinct, previously-unseen keys at
 * exactly the boundary at the same instant, the cache can settle slightly above
 * {@link #MAX_GAUGE_CACHE_ENTRIES} rather than exactly at it. It never grows without bound, and the
 * same trade-off (approximate, not exact, sizing under concurrent writes) is inherent to lock-free
 * bounded caches generally.
 */
public final class MicrometerResilienceMetrics implements ResilienceMetrics {
    private static final Logger log = LoggerFactory.getLogger(MicrometerResilienceMetrics.class);
    private static final String TAG_NAME = "name";
    private static final String TAG_CAUSE = "cause";
    private static final String TAG_OUTCOME = "outcome";
    static final int MAX_GAUGE_CACHE_ENTRIES = 10_000;

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> gaugeHolders = new ConcurrentHashMap<>();
    private final AtomicBoolean gaugeCacheBoundWarningLogged = new AtomicBoolean(false);

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
        var key = metricName + "|" + name;
        var holder = gaugeHolders.get(key);
        if (holder == null) {
            if (gaugeHolders.size() >= MAX_GAUGE_CACHE_ENTRIES) {
                warnGaugeCacheBoundReached();
                return;
            }
            holder = gaugeHolders.computeIfAbsent(key, k -> {
                var reference = new AtomicLong(Double.doubleToLongBits(value));
                registry.gauge(metricName, Tags.of(TAG_NAME, name), reference,
                    ref -> Double.longBitsToDouble(ref.get()));
                return reference;
            });
        }
        holder.set(Double.doubleToLongBits(value));
    }

    private void warnGaugeCacheBoundReached() {
        if (gaugeCacheBoundWarningLogged.compareAndSet(false, true)) {
            log.warn("Gauge cache reached its bound of {} distinct metric/name combinations; further "
                + "distinct combinations will not be registered as gauges. This usually means pattern "
                + "names are not static, contrary to the required contract.", MAX_GAUGE_CACHE_ENTRIES);
        }
    }
}
