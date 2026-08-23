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
 * {@link ResilienceMetrics#observe(Snapshot)} or {@link ResilienceMetrics#observe(Counters)}
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
        this.causeAllowlist = Set.copyOf(causeAllowlist);
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
                safeObserve(new RetryCounters.AttemptFailed(e.name(), resolveCause(e.error())));
            case RetryEvent.Success e ->
                safeObserve(new RetryCounters.Success(e.name(), e.totalAttempts()));
            case RetryEvent.Exhausted e ->
                safeObserve(new RetryCounters.Exhausted(e.name(), resolveCause(e.lastError())));
            case RetryEvent.Rejected e ->
                safeObserve(new RetryCounters.Rejected(e.name(), resolveCause(e.error())));
            case RetryEvent.Interrupted e ->
                safeObserve(new RetryCounters.Interrupted(e.name(), resolveCause(e.lastError())));
        }
    }

    private void handleTimeout(TimeoutEvent event) {
        switch (event) {
            case TimeoutEvent.Succeeded e ->
                safeObserve(new TimeoutCounters.Succeeded(e.name(), e.elapsed()));
            case TimeoutEvent.Failed e ->
                safeObserve(new TimeoutCounters.Failed(e.name(), resolveCause(e.error())));
            case TimeoutEvent.TimedOut e ->
                safeObserve(new TimeoutCounters.TimedOut(e.name()));
            case TimeoutEvent.AbandonedWorkerSucceeded e ->
                safeObserve(new TimeoutCounters.Abandoned(e.name(),
                    TimeoutCounters.AbandonedOutcome.SUCCEEDED));
            case TimeoutEvent.AbandonedWorkerFailed e ->
                safeObserve(new TimeoutCounters.Abandoned(e.name(),
                    TimeoutCounters.AbandonedOutcome.FAILED));
        }
    }

    private void handleCircuitBreaker(CircuitBreakerEvent event) {
        switch (event) {
            case CircuitBreakerEvent.CallRecorded e -> {
                safeObserve(new CircuitBreakerCounters.CallRecorded(e.name(), e.isSuccessful(),
                    e.elapsedTime()));
                safeObserve(new CircuitBreakerSnapshot.FailureRate(e.name(), e.currentFailureRate()));
            }
            case CircuitBreakerEvent.Opened e -> {
                safeObserve(new CircuitBreakerCounters.Transition(e.name(),
                    CircuitBreakerSnapshot.Phase.OPEN, e.reason()));
                safeObserve(new CircuitBreakerSnapshot.State(e.name(), CircuitBreakerSnapshot.Phase.OPEN));
            }
            case CircuitBreakerEvent.HalfOpened e -> {
                safeObserve(new CircuitBreakerCounters.Transition(e.name(),
                    CircuitBreakerSnapshot.Phase.HALF_OPEN, null));
                safeObserve(new CircuitBreakerSnapshot.State(e.name(), CircuitBreakerSnapshot.Phase.HALF_OPEN));
            }
            case CircuitBreakerEvent.Closed e -> {
                safeObserve(new CircuitBreakerCounters.Transition(e.name(),
                    CircuitBreakerSnapshot.Phase.CLOSED, null));
                safeObserve(new CircuitBreakerCounters.ClosedFromHalfOpen(e.name(),
                    e.numberOfSuccessfulTestCalls()));
                safeObserve(new CircuitBreakerSnapshot.State(e.name(), CircuitBreakerSnapshot.Phase.CLOSED));
            }
            case CircuitBreakerEvent.Rejected e ->
                safeObserve(new CircuitBreakerCounters.Rejected(e.name(), e.phase()));
        }
    }

    private void handleBulkhead(BulkheadEvent event) {
        switch (event) {
            case BulkheadEvent.Permitted e -> {
                safeObserve(new BulkheadCounters.Call(e.name(), BulkheadCounters.Outcome.PERMITTED));
                safeObserve(new BulkheadSnapshot.ActiveCalls(e.name(), e.activeCalls()));
            }
            case BulkheadEvent.Rejected e ->
                safeObserve(new BulkheadCounters.Call(e.name(), BulkheadCounters.Outcome.REJECTED));
            case BulkheadEvent.Finished e ->
                safeObserve(new BulkheadSnapshot.ActiveCalls(e.name(), e.activeCalls()));
        }
    }

    private void handleRateLimiter(RateLimiterEvent event) {
        switch (event) {
            case RateLimiterEvent.Permitted e -> {
                safeObserve(new RateLimiterCounters.Call(e.name(),
                    RateLimiterCounters.Outcome.PERMITTED));
                safeObserve(new RateLimiterSnapshot.RemainingPermits(e.name(),
                    e.remainingPermits()));
            }
            case RateLimiterEvent.Rejected e ->
                safeObserve(new RateLimiterCounters.Call(e.name(),
                    RateLimiterCounters.Outcome.REJECTED));
        }
    }

    private void handlePolicy(PolicyValidationWarning event) {
        safeObserve(new PolicyCounters.ValidationWarning(event.outer(), event.inner()));
    }

    /**
     * Resolves an exception to a cause tag, respecting the allowlist.
     * <p>
     * Matching is subtype-aware: an allowlisted class matches the thrown exception if it
     * {@code isInstance} of it, not only on exact class equality — the same matching direction
     * already used elsewhere in this library for exception classification. When the thrown
     * exception matches more than one allowlisted ancestor (e.g. both {@code IOException} and
     * {@code Exception} are allowlisted and a {@code SocketException} is thrown), the most
     * specific matching allowlisted class is used, never the thrown exception's own runtime
     * class, so the number of distinct {@code cause} values stays bounded at
     * {@code allowlist.size() + 1} exactly, regardless of how many concrete subtypes are thrown.
     * Returns "other" if no allowlisted type matches.
     * Returns null if the allowlist is empty (cause tagging disabled).
     */
    private String resolveCause(Throwable throwable) {
        if (causeAllowlist.isEmpty()) {
            return null;
        }
        Class<? extends Throwable> mostSpecificMatch = null;
        for (var candidate : causeAllowlist) {
            if (candidate.isInstance(throwable)
                && (mostSpecificMatch == null || mostSpecificMatch.isAssignableFrom(candidate))) {
                mostSpecificMatch = candidate;
            }
        }
        return mostSpecificMatch != null ? mostSpecificMatch.getSimpleName() : CAUSE_OTHER;
    }

    /**
     * Wraps each {@code observe} call in its own try/catch so that if one backend call fails,
     * the next one still executes. This is critical when a single event drives multiple
     * {@code observe(...)} calls (e.g., CallRecorded emits both a counter and a snapshot).
     */
    private void safeObserve(Snapshot snapshot) {
        try {
            metrics.observe(snapshot);
        } catch (Exception e) {
            log.warn("Error recording snapshot: {}", snapshot, e);
        }
    }

    /**
     * Wraps each {@code observe} call in its own try/catch so that if one backend call fails,
     * the next one still executes. This is critical when a single event drives multiple
     * {@code observe(...)} calls (e.g., CallRecorded emits both a counter and a snapshot).
     */
    private void safeObserve(Counters counters) {
        try {
            metrics.observe(counters);
        } catch (Exception e) {
            log.warn("Error recording counters: {}", counters, e);
        }
    }
}
