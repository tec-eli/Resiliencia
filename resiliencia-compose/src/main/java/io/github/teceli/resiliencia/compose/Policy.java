package io.github.teceli.resiliencia.compose;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilienciaException;
import io.github.teceli.resiliencia.core.api.ResilienciaTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
     * and let construction proceed.
     *
     * Extension point: to flag a new ordering (e.g. future Bulkhead or RateLimiter rules), add
     * an entry to {@link #ORDERING_RULES}; no other code needs to change.
     */
    private record OrderingRule(PatternKind outer, PatternKind inner, Severity severity,
                                String problem, String suggestedFix) {
        enum Severity { ERROR, WARN }
    }

    private static final List<OrderingRule> ORDERING_RULES = List.of(
            new OrderingRule(PatternKind.RETRY, PatternKind.CIRCUIT_BREAKER, OrderingRule.Severity.ERROR,
                    "Retry wraps CircuitBreaker: the retry loop would burn its attempt budget against an "
                            + "already-open circuit, which fails fast on every attempt — no legitimate use case",
                    "Compose CircuitBreaker before Retry so the circuit is checked outside the retry loop, "
                            + "e.g. Policy.compose(circuitBreaker).and(retry), or use Policy.useDefault(...)"),
            new OrderingRule(PatternKind.TIMEOUT, PatternKind.RETRY, OrderingRule.Severity.WARN,
                    "Timeout wraps Retry: this ordering is valid for a per-attempt timeout, which is what the "
                            + "library implements today, but may be a mistake if an overall deadline across the "
                            + "whole retry loop was intended instead (not modeled yet)",
                    "If a per-attempt timeout was intended, prefer composing Retry before Timeout, "
                            + "e.g. Policy.compose(retry).and(timeout), or use Policy.useDefault(...)"));

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
     * Create a policy from the given patterns, composed in the library's recommended default
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
                    "Call Policy.useDefault(patterns) with at least one Resilient pattern");
        }
        var sorted = new ArrayList<Resilient<T>>(patterns.length);
        for (var pattern : patterns) {
            sorted.add(Objects.requireNonNull(pattern, PATTERN_MUST_NOT_BE_NULL));
        }
        sorted.sort(Comparator.comparingInt(pattern -> defaultOrderRank(pattern.patternKind())));

        var policy = compose(sorted.getFirst());
        for (var pattern : sorted.subList(1, sorted.size())) {
            policy = policy.and(pattern);
        }
        return policy;
    }

    /**
     * Position of each pattern kind in the recommended default order; lower means further out.
     */
    private static int defaultOrderRank(PatternKind kind) {
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
                switch (rule.severity()) {
                    case ERROR -> throw new InvalidPolicyException(rule.problem(), rule.suggestedFix());
                    case WARN -> log.warn("{}. {}.", rule.problem(), rule.suggestedFix());
                }
            }
        }
    }

    /**
     * Execute the operation through the pattern chain on the calling thread, blocking until complete.
     * Returns the result, or throws the original ResilienciaException subtype produced by whichever
     * pattern failed (e.g. RetryExhaustedException), falling back to a generic ResilienciaException
     * for any other unchecked exception.
     *
     * @throws ResilienciaException if the operation fails after passing through the pattern chain
     */
    @Override
    public T call(Operation<T> operation) throws ResilienciaException {
        return switch (outcome(operation)) {
            case Outcome.Success<T>(T value) -> value;
            case Outcome.TimedOut<T>(var timeout) -> throw new ResilienciaTimeoutException(timeout);
            case Outcome.Failure<T>(ResilienciaException cause) -> throw cause;
            case Outcome.Failure<T>(Throwable cause) -> throw new ResilienciaException("Policy execution failed", cause);
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
        } catch (ResilienciaTimeoutException e) {
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
