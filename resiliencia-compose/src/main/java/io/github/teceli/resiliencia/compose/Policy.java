package io.github.teceli.resiliencia.compose;

import io.github.teceli.resiliencia.core.api.InvalidPolicyException;
import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilienciaException;

import java.util.ArrayList;
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
     */
    public Policy<T> and(Resilient<T> pattern) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        var newPatterns = new ArrayList<>(patterns);
        newPatterns.add(pattern);
        return new Policy<>(newPatterns);
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
