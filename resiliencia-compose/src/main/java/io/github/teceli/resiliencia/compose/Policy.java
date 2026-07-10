package io.github.teceli.resiliencia.compose;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilientException;
import io.github.teceli.resiliencia.core.api.ResilientTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Fluent composition of multiple resilience patterns.
 * The first pattern passed to {@link #compose} is the outermost layer, invoked first.
 * Each pattern added via {@link #and} becomes the new innermost layer, closer to the operation
 * than every pattern added before it.
 *
 * Example: Policy.compose(retry).call(op) executes op with retry protection.
 * Example: Policy.compose(circuitBreaker).and(retry).call(op) checks the circuit breaker first;
 * retry sits inside it, closest to the operation.
 */
public final class Policy<T> implements Resilient<T> {
    private static final Logger log = LoggerFactory.getLogger(Policy.class);
    private static final String PATTERN_MUST_NOT_BE_NULL = "Pattern must not be null";

    /**
     * A known-bad ordering of two pattern kinds: {@code outer} sitting anywhere further out in
     * the chain than {@code inner} — not necessarily adjacent, other patterns may sit between
     * them. ERROR rules reject construction with {@link InvalidPolicyException}; WARN rules log
     * and let construction proceed, unless {@code suppressWhen} matches the pattern instance
     * being added (e.g. a Retry that already bounds its own total duration).
     *
     * Extension point: to flag a new ordering, add an entry to {@link #ORDERING_RULES}; no other
     * code needs to change.
     */
    private record OrderingRule(PatternKind outer, PatternKind inner, Severity severity,
                                String problem, String suggestedFix, Predicate<Resilient<?>> suppressWhen) {
        enum Severity { ERROR, WARN }
    }

    private static final Predicate<Resilient<?>> NEVER_SUPPRESS = pattern -> false;

    private static final List<OrderingRule> ORDERING_RULES = List.of(
            new OrderingRule(PatternKind.RETRY, PatternKind.CIRCUIT_BREAKER, OrderingRule.Severity.ERROR,
                    "Retry wraps CircuitBreaker: the retry loop would burn its attempt budget against an "
                            + "already-open circuit, which fails fast on every attempt — no legitimate use case",
                    "Compose CircuitBreaker before Retry so the circuit is checked outside the retry loop, "
                            + "e.g. Policy.compose(circuitBreaker).and(retry), or use Policy.useOptimumOrder(...)",
                    NEVER_SUPPRESS),
            new OrderingRule(PatternKind.TIMEOUT, PatternKind.RETRY, OrderingRule.Severity.WARN,
                    "Timeout wraps Retry: this ordering is valid for a per-attempt timeout, which is what the "
                            + "library implements today, but may be a mistake if an overall deadline across the "
                            + "whole retry loop was intended instead",
                    "If a per-attempt timeout was intended, prefer composing Retry before Timeout, "
                            + "e.g. Policy.compose(retry).and(timeout), or use Policy.useOptimumOrder(...). If an "
                            + "overall deadline was intended, configure Retry.withOverallDeadline(...) instead, "
                            + "which suppresses this warning",
                    Resilient::hasOwnDeadline),
            new OrderingRule(PatternKind.BULKHEAD, PatternKind.CIRCUIT_BREAKER, OrderingRule.Severity.ERROR,
                    "Bulkhead wraps CircuitBreaker: a permit is reserved before the circuit state is known, "
                            + "wasting bulkhead capacity on a call that fails immediately once the circuit check "
                            + "runs — no legitimate use case",
                    "Compose CircuitBreaker before Bulkhead so the circuit is checked before a permit is "
                            + "reserved, e.g. Policy.compose(circuitBreaker).and(bulkhead), or use "
                            + "Policy.useOptimumOrder(...)",
                    NEVER_SUPPRESS),
            new OrderingRule(PatternKind.BULKHEAD, PatternKind.RATE_LIMITER, OrderingRule.Severity.ERROR,
                    "Bulkhead wraps RateLimiter: a permit is reserved before the rate limit is checked, "
                            + "wasting bulkhead capacity on a call that gets rejected once the rate-limit check "
                            + "runs — no legitimate use case",
                    "Compose RateLimiter before Bulkhead so the rate limit is checked before a permit is "
                            + "reserved, e.g. Policy.compose(rateLimiter).and(bulkhead), or use "
                            + "Policy.useOptimumOrder(...)",
                    NEVER_SUPPRESS),
            new OrderingRule(PatternKind.RETRY, PatternKind.RATE_LIMITER, OrderingRule.Severity.WARN,
                    "Retry wraps RateLimiter: each attempt is independently subject to the rate limit instead "
                            + "of the whole call being gated once, outermost. Legitimate when the limiter exists "
                            + "to bound the rate of outbound calls per attempt. Note: Retry's default "
                            + "shouldRetry only matches IOException — it will not retry a RateLimiterException "
                            + "unless shouldRetry is extended to cover it",
                    "If the limiter is meant to gate the whole call once, prefer composing RateLimiter before "
                            + "Retry, e.g. Policy.compose(rateLimiter).and(retry), or use "
                            + "Policy.useOptimumOrder(...). If this per-attempt ordering is intended, extend "
                            + "shouldRetry to also match RateLimiterException",
                    NEVER_SUPPRESS),
            new OrderingRule(PatternKind.RETRY, PatternKind.BULKHEAD, OrderingRule.Severity.WARN,
                    "Retry wraps Bulkhead: the permit is re-acquired per attempt instead of held for the whole "
                            + "retry loop. Legitimate when the intent is to avoid monopolizing a permit during "
                            + "backoff waits. Note: Retry's default shouldRetry only matches IOException — it "
                            + "will not retry a BulkheadFullException unless shouldRetry is extended to cover it",
                    "If one permit should be held for the whole retry loop, prefer composing Bulkhead before "
                            + "Retry, e.g. Policy.compose(bulkhead).and(retry), or use Policy.useOptimumOrder(...). "
                            + "If this per-attempt ordering is intended, extend shouldRetry to also match "
                            + "BulkheadFullException",
                    NEVER_SUPPRESS));

    private final List<Resilient<T>> patterns;

    private Policy(List<Resilient<T>> patterns) {
        if (patterns.isEmpty()) {
            throw new InvalidPolicyException(
                    "Policy must have at least one pattern",
                    "Call Policy.compose(pattern) with at least one Resilient pattern");
        }
        this.patterns = List.copyOf(patterns);
    }

    /**
     * Create a policy with a single pattern. This pattern becomes the outermost layer.
     */
    public static <T> Policy<T> compose(Resilient<T> pattern) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        return new Policy<>(List.of(pattern));
    }

    /**
     * Add another pattern to this policy. The new pattern becomes the innermost layer,
     * closest to the operation; patterns added earlier stay further out.
     *
     * The new pattern is checked against every pattern already in the chain (transitively, not
     * just the adjacent one) for known-bad orderings: Retry wrapping CircuitBreaker is rejected
     * with {@link InvalidPolicyException}; Timeout wrapping Retry logs a WARN but proceeds.
     *
     * @throws InvalidPolicyException if the resulting ordering is known to be broken at runtime
     */
    public Policy<T> and(Resilient<T> pattern) {
        Objects.requireNonNull(pattern, PATTERN_MUST_NOT_BE_NULL);
        validateOrdering(patterns, pattern);
        var newPatterns = new ArrayList<>(patterns);
        newPatterns.add(pattern);
        return new Policy<>(newPatterns);
    }

    /**
     * Create a policy from the given patterns, composed in the library's optimum
     * order regardless of the order they are passed in — outermost to innermost:
     * RateLimiter, CircuitBreaker, Bulkhead, Retry, Timeout.
     *
     * The result is a plain {@code Policy}, identical to what the equivalent
     * {@code compose(...).and(...)} chain produces, and goes through the same ordering guardrail.
     * The sort is stable: patterns of the same kind keep the relative order they were passed in.
     */
    @SafeVarargs
    public static <T> Policy<T> useOptimumOrder(Resilient<T>... patterns) {
        Objects.requireNonNull(patterns, PATTERN_MUST_NOT_BE_NULL);
        if (patterns.length == 0) {
            throw new InvalidPolicyException(
                    "Policy must have at least one pattern",
                    "Call Policy.useOptimumOrder(patterns) with at least one Resilient pattern");
        }
        var sorted = new ArrayList<Resilient<T>>(patterns.length);
        for (var pattern : patterns) {
            sorted.add(Objects.requireNonNull(pattern, PATTERN_MUST_NOT_BE_NULL));
        }
        sorted.sort(Comparator.comparingInt(pattern -> optimumOrderRank(pattern.patternKind())));

        var policy = compose(sorted.getFirst());
        for (var pattern : sorted.subList(1, sorted.size())) {
            policy = policy.and(pattern);
        }
        return policy;
    }

    /**
     * Position of each pattern kind in the optimum order; lower means further out.
     */
    private static int optimumOrderRank(PatternKind kind) {
        return switch (kind) {
            case RATE_LIMITER -> 0;
            case CIRCUIT_BREAKER -> 1;
            case BULKHEAD -> 2;
            case RETRY -> 3;
            case TIMEOUT -> 4;
            // CUSTOM patterns have unknown semantics, so they go innermost: every known
            // pattern's guarantee still applies around them, and sitting innermost they can
            // never form a known-bad (outer, inner) pair with the built-in kinds.
            case CUSTOM -> 5;
        };
    }

    /**
     * Check the pattern being added (the new innermost layer) against every pattern already in
     * the chain, applying each matching {@link OrderingRule}.
     */
    private static <T> void validateOrdering(List<Resilient<T>> outerPatterns, Resilient<T> newPattern) {
        for (var rule : ORDERING_RULES) {
            if (rule.inner() == newPattern.patternKind() &&
                outerPatterns.stream().anyMatch(outer -> outer.patternKind() == rule.outer())) {
                if (rule.severity() == OrderingRule.Severity.ERROR) {
                    throw new InvalidPolicyException(rule.problem(), rule.suggestedFix());
                } else if (rule.severity() == OrderingRule.Severity.WARN && !rule.suppressWhen().test(newPattern)) {
                    log.warn("{}. {}.", rule.problem(), rule.suggestedFix());
                }
            }
        }
    }

    /**
     * Execute the operation through the pattern chain on the calling thread, blocking until complete.
     * Returns the result, or throws whichever exception the innermost failing pattern throws.
     * Policy propagates RuntimeExceptions (including all ResilientException subtypes) as-is, without wrapping.
     * For Throwable types that are not RuntimeException (e.g., Error), wraps in ResilientException as a safety net.
     *
     * @throws ResilientException if the operation fails after passing through the pattern chain
     */
    @Override
    public T call(Operation<T> operation) throws ResilientException {
        return switch (outcome(operation)) {
            case Outcome.Success<T>(T value) -> value;
            case Outcome.TimedOut<T>(var timeout) -> throw new ResilientTimeoutException(timeout);
            case Outcome.Failure<T>(RuntimeException cause) -> throw cause;
            case Outcome.Failure<T>(Throwable cause) ->
                    throw new ResilientException("Policy execution failed", cause);
        };
    }

    /**
     * Execute and capture outcome (never throws).
     * Chains all patterns so each wraps the operation before execution.
     */
    @Override
    public Outcome<T> outcome(Operation<T> operation) {
        var chainedOp = buildChain(operation, 0);
        try {
            var result = chainedOp.execute();
            return new Outcome.Success<>(result);
        } catch (ResilientTimeoutException e) {
            return new Outcome.TimedOut<>(e.timeout());
        } catch (Exception e) {
            return new Outcome.Failure<>(e);
        }
    }

    /**
     * Build the chain recursively: each pattern wraps the next.
     * Pattern 0 (the first one composed) is outermost and executes first;
     * the last pattern added wraps the original operation directly.
     */
    private Operation<T> buildChain(Operation<T> innerOp, int patternIndex) {
        if (patternIndex == patterns.size()) {
            return innerOp;
        }

        var pattern = patterns.get(patternIndex);
        var nextOp = buildChain(innerOp, patternIndex + 1);

        return () -> pattern.call(nextOp);
    }
}
