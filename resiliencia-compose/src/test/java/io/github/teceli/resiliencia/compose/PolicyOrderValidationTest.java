package io.github.teceli.resiliencia.compose;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilienciaException;
import io.github.teceli.resiliencia.patterns.retry.Retry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for Policy pattern order validation and Policy.useOptimumOrder order resolution.
 * Uses fake patterns per kind — the real Timeout/CircuitBreaker/Bulkhead/RateLimiter
 * patterns don't exist yet; the guardrail only depends on {@link Resilient#patternKind()}.
 */
class PolicyOrderValidationTest {

    @Test
    void should_throwInvalidPolicyException_when_retryWrapsCircuitBreaker() {
        var retry = fakePattern(PatternKind.RETRY);
        var circuitBreaker = fakePattern(PatternKind.CIRCUIT_BREAKER);

        var exception = assertThrows(InvalidPolicyException.class, () ->
            Policy.compose(retry).and(circuitBreaker));
        assertThat(exception)
                .hasMessageContaining("Retry")
                .hasMessageContaining("CircuitBreaker");
        assertThat(exception.suggestedFix())
                .asString()
                .contains("CircuitBreaker before Retry");
    }

    @Test
    void should_throwInvalidPolicyException_when_retryWrapsCircuitBreakerTransitively() {
        // Retry -> Bulkhead -> CircuitBreaker: the conflicting pair is not adjacent,
        // but the Retry sitting further out must still reject the CircuitBreaker added last.
        var retry = fakePattern(PatternKind.RETRY);
        var bulkhead = fakePattern(PatternKind.BULKHEAD);
        var circuitBreaker = fakePattern(PatternKind.CIRCUIT_BREAKER);

        var retryThenBulkhead = Policy.compose(retry).and(bulkhead);

        assertThrows(InvalidPolicyException.class, () ->
            retryThenBulkhead.and(circuitBreaker));
    }

    @Test
    void should_throwInvalidPolicyException_when_realRetryWrapsCircuitBreaker() {
        var retry = Retry.<String>create().withMaxAttempts(2).withInitialDelay(10);
        var circuitBreaker = fakePattern(PatternKind.CIRCUIT_BREAKER);

        assertThrows(InvalidPolicyException.class, () ->
            Policy.compose(retry).and(circuitBreaker));
    }

    @Test
    void should_logWarnAndSucceed_when_timeoutWrapsRetry() {
        var timeout = fakePattern(PatternKind.TIMEOUT);
        var retry = fakePattern(PatternKind.RETRY);

        var stderr = captureStdErr(() ->
                assertThatNoException().isThrownBy(() -> Policy.compose(timeout).and(retry)));

        assertThat(stderr)
                .contains("WARN")
                .contains("Timeout wraps Retry");
    }

    @Test
    void should_executeConstructedPolicy_when_timeoutWrapsRetry() {
        var policy = Policy.compose(fakePattern(PatternKind.TIMEOUT))
                .and(fakePattern(PatternKind.RETRY));

        assertThat(policy.call(() -> "done")).isEqualTo("done");
    }

    @Test
    void should_resolveRecommendedOrder_when_useOptimumOrderReceivesShuffledPatterns() {
        var callOrder = new ArrayList<String>();

        var policy = Policy.useOptimumOrder(
                recordingPattern(PatternKind.TIMEOUT, callOrder),
                recordingPattern(PatternKind.BULKHEAD, callOrder),
                recordingPattern(PatternKind.RETRY, callOrder),
                recordingPattern(PatternKind.RATE_LIMITER, callOrder),
                recordingPattern(PatternKind.CIRCUIT_BREAKER, callOrder));

        var result = policy.call(() -> {
            callOrder.add("operation");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(callOrder).containsExactly(
                "RATE_LIMITER", "CIRCUIT_BREAKER", "BULKHEAD", "RETRY", "TIMEOUT", "operation");
    }

    @Test
    void should_placeCustomPatternInnermost_when_useOptimumOrderReceivesUnknownKind() {
        var callOrder = new ArrayList<String>();

        var policy = Policy.useOptimumOrder(
                recordingPattern(PatternKind.CUSTOM, callOrder),
                recordingPattern(PatternKind.RETRY, callOrder),
                recordingPattern(PatternKind.RATE_LIMITER, callOrder));

        policy.call(() -> {
            callOrder.add("operation");
            return "done";
        });

        assertThat(callOrder).containsExactly("RATE_LIMITER", "RETRY", "CUSTOM", "operation");
    }

    @Test
    void should_constructWithoutWarnOrException_when_fullRecommendedOrderComposedExplicitly() {
        var stderr = captureStdErr(() ->
                assertThatNoException().isThrownBy(() -> Policy.compose(fakePattern(PatternKind.RATE_LIMITER))
                        .and(fakePattern(PatternKind.CIRCUIT_BREAKER))
                        .and(fakePattern(PatternKind.BULKHEAD))
                        .and(fakePattern(PatternKind.RETRY))
                        .and(fakePattern(PatternKind.TIMEOUT))));

        assertThat(stderr).doesNotContain("WARN");
    }

    @Test
    void should_constructWithoutWarnOrException_when_useOptimumOrderResolvesShuffledInput() {
        var stderr = captureStdErr(() ->
                assertThatNoException().isThrownBy(() -> Policy.useOptimumOrder(
                        fakePattern(PatternKind.TIMEOUT),
                        fakePattern(PatternKind.RETRY),
                        fakePattern(PatternKind.CIRCUIT_BREAKER))));

        assertThat(stderr).doesNotContain("WARN");
    }

    @Test
    void should_constructWithoutWarnOrException_when_circuitBreakerWrapsRetry() {
        var stderr = captureStdErr(() ->
                assertThatNoException().isThrownBy(() -> Policy.compose(fakePattern(PatternKind.CIRCUIT_BREAKER))
                        .and(fakePattern(PatternKind.RETRY))));

        assertThat(stderr).doesNotContain("WARN");
    }

    @Test
    void should_throwInvalidPolicyException_when_useOptimumOrderReceivesNoPatterns() {
        var exception = assertThrows(InvalidPolicyException.class, Policy::<String>useOptimumOrder);
        assertThat(exception).hasMessageContaining("at least one pattern");
    }

    @Test
    void should_throwNullPointerException_when_useOptimumOrderReceivesNullPattern() {
        assertThrows(NullPointerException.class, () ->
            Policy.useOptimumOrder(fakePattern(PatternKind.RETRY), null));
    }

    /**
     * A pass-through pattern that only reports the given kind — enough for order validation,
     * which never looks at behavior.
     */
    private static Resilient<String> fakePattern(PatternKind kind) {
        return recordingPattern(kind, new ArrayList<>());
    }

    /**
     * A pass-through pattern reporting the given kind that records its kind name into
     * {@code callOrder} when invoked, so tests can assert the resolved execution order.
     */
    private static Resilient<String> recordingPattern(PatternKind kind, List<String> callOrder) {
        return new Resilient<>() {
            @Override
            public String call(Operation<String> operation) throws ResilienciaException {
                callOrder.add(kind.name());
                return operation.execute();
            }

            @Override
            public Outcome<String> outcome(Operation<String> operation) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PatternKind patternKind() {
                return kind;
            }
        };
    }

    /**
     * Runs the action while System.err is redirected to a buffer and returns what was written.
     * slf4j-simple (the test-scoped SLF4J provider) writes to System.err, resolved at write
     * time, so WARN output from Policy lands in the buffer.
     */
    private static String captureStdErr(Runnable action) {
        var original = System.err;
        var buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
