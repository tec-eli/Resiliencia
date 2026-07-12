package io.github.teceli.resiliencia.metrics;

import io.github.teceli.resiliencia.compose.PolicyValidationWarning;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;

/**
 * Translates typed events from all resilience patterns into {@link Snapshot}/{@link Counters}
 * emissions for backend-agnostic metric recording.
 * <p>
 * Each event is switched exhaustively; unknown events (custom pattern implementations) are
 * silently ignored via the default branch. Exception isolation is strict: if a
 * {@link ResilienceMetrics#record(Snapshot)} or {@link ResilienceMetrics#record(Counters)}
 * call throws, the exception is caught, logged at WARN, and processing continues. This ensures
 * that a broken metrics backend never breaks the protected call itself.
 * </p>
 * <p>
 * <strong>Cardinality control:</strong> the optional {@code causeAllowlist} restricts which
 * exception types produce a {@code cause} tag. Exception types not in the allowlist map to a
 * fixed {@code "other"} bucket instead, bounding cardinality by construction: max distinct
 * {@code cause} values = {@code allowlist.size() + 1}. Empty allowlist (the default) disables
 * cause tagging entirely.
 * </p>
 */
public final class ResilienceMetricsListener implements ResilienceEvent.Listener {
    private static final Logger log = LoggerFactory.getLogger(ResilienceMetricsListener.class);
    private static final String CAUSE_OTHER = "other";

    private final ResilienceMetrics metrics;
    private final Set<Class<? extends Throwable>> causeAllowlist;

    public ResilienceMetricsListener(ResilienceMetrics metrics) {
        this(metrics, Collections.emptySet());
    }

    public ResilienceMetricsListener(ResilienceMetrics metrics,
                                      Set<Class<? extends Throwable>> causeAllowlist) {
        this.metrics = metrics;
        this.causeAllowlist = causeAllowlist;
    }

    @Override
    public void onEvent(ResilienceEvent event) {
        switch (event) {
            case RetryEvent e -> handleRetry(e);
            case TimeoutEvent e -> handleTimeout(e);
            case CircuitBreakerEvent e -> handleCircuitBreaker(e);
            case BulkheadEvent e -> handleBulkhead(e);
            case RateLimiterEvent e -> handleRateLimiter(e);
            case PolicyValidationWarning e -> handlePolicy(e);
            default -> { } // Custom patterns / unknown ResilienceEvent implementations — ignored
        }
    }

    private void handleRetry(RetryEvent event) {
        switch (event) {
            case RetryEvent.AttemptFailed e ->
                safeRecord(new RetryCounters.AttemptFailed(e.name(), resolveCause(e.error())));
            case RetryEvent.Success e ->
                safeRecord(new RetryCounters.Success(e.name(), e.totalAttempts()));
            case RetryEvent.Exhausted e ->
                safeRecord(new RetryCounters.Exhausted(e.name(), resolveCause(e.lastError())));
            case RetryEvent.Rejected e ->
                safeRecord(new RetryCounters.Rejected(e.name(), resolveCause(e.error())));
            case RetryEvent.Interrupted e ->
                safeRecord(new RetryCounters.Interrupted(e.name(), resolveCause(e.lastError())));
        }
    }

    private void handleTimeout(TimeoutEvent event) {
        switch (event) {
            case TimeoutEvent.Succeeded e ->
                safeRecord(new TimeoutCounters.Succeeded(e.name(), e.elapsed()));
            case TimeoutEvent.Failed e ->
                safeRecord(new TimeoutCounters.Failed(e.name(), resolveCause(e.error())));
            case TimeoutEvent.TimedOut e ->
                safeRecord(new TimeoutCounters.TimedOut(e.name()));
            case TimeoutEvent.AbandonedWorkerSucceeded e ->
                safeRecord(new TimeoutCounters.Abandoned(e.name(),
                    TimeoutCounters.AbandonedOutcome.SUCCEEDED));
            case TimeoutEvent.AbandonedWorkerFailed e ->
                safeRecord(new TimeoutCounters.Abandoned(e.name(),
                    TimeoutCounters.AbandonedOutcome.FAILED));
        }
    }

    private void handleCircuitBreaker(CircuitBreakerEvent event) {
        switch (event) {
            case CircuitBreakerEvent.CallRecorded e -> {
                safeRecord(new CircuitBreakerCounters.CallRecorded(e.name(), e.isSuccessful(),
                    e.elapsedTime()));
                safeRecord(new CircuitBreakerSnapshot.FailureRate(e.name(), e.currentFailureRate()));
            }
            case CircuitBreakerEvent.Opened e ->
                safeRecord(new CircuitBreakerCounters.Transition(e.name(),
                    CircuitBreakerSnapshot.Phase.OPEN, e.reason()));
            case CircuitBreakerEvent.HalfOpened e ->
                safeRecord(new CircuitBreakerCounters.Transition(e.name(),
                    CircuitBreakerSnapshot.Phase.HALF_OPEN, null));
            case CircuitBreakerEvent.Closed e -> {
                safeRecord(new CircuitBreakerCounters.Transition(e.name(),
                    CircuitBreakerSnapshot.Phase.CLOSED, null));
                safeRecord(new CircuitBreakerCounters.ClosedFromHalfOpen(e.name(),
                    e.numberOfSuccessfulTestCalls()));
            }
            case CircuitBreakerEvent.Rejected e ->
                safeRecord(new CircuitBreakerCounters.Rejected(e.name(), e.phase()));
        }
    }

    private void handleBulkhead(BulkheadEvent event) {
        switch (event) {
            case BulkheadEvent.Permitted e -> {
                safeRecord(new BulkheadCounters.Call(e.name(), BulkheadCounters.Outcome.PERMITTED));
                safeRecord(new BulkheadSnapshot.ActiveCalls(e.name(), e.activeCalls()));
            }
            case BulkheadEvent.Rejected e ->
                safeRecord(new BulkheadCounters.Call(e.name(), BulkheadCounters.Outcome.REJECTED));
            case BulkheadEvent.Finished e ->
                safeRecord(new BulkheadSnapshot.ActiveCalls(e.name(), e.activeCalls()));
        }
    }

    private void handleRateLimiter(RateLimiterEvent event) {
        switch (event) {
            case RateLimiterEvent.Permitted e -> {
                safeRecord(new RateLimiterCounters.Call(e.name(),
                    RateLimiterCounters.Outcome.PERMITTED));
                safeRecord(new RateLimiterSnapshot.RemainingPermits(e.name(),
                    e.remainingPermits()));
            }
            case RateLimiterEvent.Rejected e ->
                safeRecord(new RateLimiterCounters.Call(e.name(),
                    RateLimiterCounters.Outcome.REJECTED));
        }
    }

    private void handlePolicy(PolicyValidationWarning event) {
        safeRecord(new PolicyCounters.ValidationWarning(event.outer(), event.inner()));
    }

    /**
     * Resolves an exception to a cause tag, respecting the allowlist.
     * Returns the simple class name if the exception type is in the allowlist,
     * otherwise returns a fixed "other" bucket to prevent unbounded cardinality.
     * Returns null if the allowlist is empty (cause tagging disabled).
     */
    private String resolveCause(Throwable throwable) {
        if (causeAllowlist.isEmpty()) {
            return null;
        }
        var exceptionType = throwable.getClass();
        if (causeAllowlist.contains(exceptionType)) {
            return exceptionType.getSimpleName();
        }
        return CAUSE_OTHER;
    }

    /**
     * Wraps each {@code record} call in its own try/catch so that if one backend call fails,
     * the next one still executes. This is critical when a single event drives multiple
     * {@code record(...)} calls (e.g., CallRecorded emits both a counter and a snapshot).
     */
    private void safeRecord(Snapshot snapshot) {
        try {
            metrics.record(snapshot);
        } catch (Exception e) {
            log.warn("Error recording snapshot: {}", snapshot, e);
        }
    }

    /**
     * Wraps each {@code record} call in its own try/catch so that if one backend call fails,
     * the next one still executes. This is critical when a single event drives multiple
     * {@code record(...)} calls (e.g., CallRecorded emits both a counter and a snapshot).
     */
    private void safeRecord(Counters counters) {
        try {
            metrics.record(counters);
        } catch (Exception e) {
            log.warn("Error recording counters: {}", counters, e);
        }
    }
}
