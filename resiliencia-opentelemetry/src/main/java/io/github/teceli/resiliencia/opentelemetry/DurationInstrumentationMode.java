package io.github.teceli.resiliencia.opentelemetry;

/**
 * How {@link OpenTelemetryResilienceMetrics} records duration metrics
 * ({@code resilience.timeout.duration}, {@code resilience.circuitbreaker.calls}).
 *
 * <p>Unlike Micrometer's percentile histograms, there is no "don't opt in" lever available to
 * resiliencia's own code for the OTel SDK's default histogram aggregation: {@code
 * DoubleExplicitBucketHistogramAggregator} synchronizes on every recorded value, unconditionally
 * (see {@code docs/architecture/metrics/metrics.md}'s "Backend audit" section). This closed,
 * two-value enum keeps the safe default enforced by the API shape itself, rather than a
 * consumer-supplied strategy that could reintroduce the same unbounded risk.
 */
public enum DurationInstrumentationMode {

    /**
     * Duration recorded as a lock-free counter pair — a count and a summed duration, both backed by
     * {@code LongSumAggregator}/{@code DoubleSumAggregator}. Mean is derivable ({@code sum / count})
     * by the backend at query time; no percentile/distribution data; zero per-call pinning risk by
     * construction. Default.
     */
    SAFE,

    /**
     * Duration recorded via {@code DoubleHistogram.record(...)}, yielding full percentile/
     * distribution data, with the documented per-call {@code synchronized} pinning risk from the
     * OTel SDK's default explicit-bucket histogram aggregation. Explicit opt-in only.
     */
    DETAILED
}
