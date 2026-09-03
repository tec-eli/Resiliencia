package io.github.teceli.resiliencia.opentelemetry;

/**
 * Canonical {@code resilience.<pattern>.<metric>} names, one per {@code Snapshot}/{@code Counters}
 * variant. Kept consistent with {@code resiliencia-micrometer}'s naming.
 *
 * <p>{@code _DURATION_COUNT}/{@code _DURATION_SUM} are only used in
 * {@link DurationInstrumentationMode#SAFE} — the base duration name itself is used for the
 * {@code DoubleHistogram} instrument in {@link DurationInstrumentationMode#DETAILED}.
 */
final class MetricNames {

    static final String RETRY_ATTEMPTS = "resilience.retry.attempts";
    static final String RETRY_SUCCESS = "resilience.retry.success";
    static final String RETRY_EXHAUSTED = "resilience.retry.exhausted";
    static final String RETRY_REJECTED = "resilience.retry.rejected";
    static final String RETRY_INTERRUPTED = "resilience.retry.interrupted";

    static final String TIMEOUT_DURATION = "resilience.timeout.duration";
    static final String TIMEOUT_DURATION_COUNT = "resilience.timeout.duration.count";
    static final String TIMEOUT_DURATION_SUM = "resilience.timeout.duration.sum";
    static final String TIMEOUT_FAILED = "resilience.timeout.failed";
    static final String TIMEOUT_TIMED_OUT = "resilience.timeout.timed_out";
    static final String TIMEOUT_ABANDONED = "resilience.timeout.abandoned";

    static final String CIRCUIT_BREAKER_STATE = "resilience.circuitbreaker.state";
    static final String CIRCUIT_BREAKER_TRANSITIONS = "resilience.circuitbreaker.transitions";
    static final String CIRCUIT_BREAKER_CLOSED_TEST_CALLS = "resilience.circuitbreaker.closed_test_calls";
    static final String CIRCUIT_BREAKER_FAILURE_RATE = "resilience.circuitbreaker.failure_rate";
    static final String CIRCUIT_BREAKER_CALLS = "resilience.circuitbreaker.calls";
    static final String CIRCUIT_BREAKER_CALLS_COUNT = "resilience.circuitbreaker.calls.count";
    static final String CIRCUIT_BREAKER_CALLS_SUM = "resilience.circuitbreaker.calls.sum";
    static final String CIRCUIT_BREAKER_REJECTED = "resilience.circuitbreaker.rejected";

    static final String BULKHEAD_ACTIVE_CALLS = "resilience.bulkhead.active_calls";
    static final String BULKHEAD_CALLS = "resilience.bulkhead.calls";

    static final String RATE_LIMITER_REMAINING_PERMITS = "resilience.ratelimiter.remaining_permits";
    static final String RATE_LIMITER_CALLS = "resilience.ratelimiter.calls";

    static final String POLICY_VALIDATION_WARNINGS = "resilience.policy.validation_warnings";

    private MetricNames() {
    }
}
