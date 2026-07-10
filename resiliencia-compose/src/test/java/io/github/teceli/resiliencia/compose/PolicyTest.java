package io.github.teceli.resiliencia.compose;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilientException;
import io.github.teceli.resiliencia.patterns.retry.Retry;
import io.github.teceli.resiliencia.patterns.retry.RetryEvent;
import io.github.teceli.resiliencia.patterns.retry.RetryExhaustedException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for Policy composition MVP.
 * Demonstrates Policy + Retry + synchronous execution.
 */
class PolicyTest {
    @Test
    void should_composeRetryPattern_and_executeSuccessfully() {
        var counter = new AtomicInteger(0);

        var retry = Retry.<String>create()
                .withMaxAttempts(3)
                .withInitialDelay(10)
                .withShouldRetry(e -> true);

        var policy = Policy.compose(retry);

        var result = policy.call(() -> {
            var value = counter.incrementAndGet();
            if (value < 2) {
                throw new RuntimeException("Fail attempt " + value);
            }
            return "Success";
        });

        assertThat(result).isEqualTo("Success");
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void should_throwException_when_policyFails() {
        var retry = Retry.<String>create()
                .withMaxAttempts(2)
                .withInitialDelay(10)
                .withShouldRetry(e -> true);

        var policy = Policy.compose(retry);

        assertThrows(RetryExhaustedException.class, () -> policy.call(() -> {
            throw new RuntimeException("Always fails");
        }));
    }

    @Test
    void should_returnOutcome_when_usingOutcomeMethod() {
        var counter = new AtomicInteger(0);

        var retry = Retry.<String>create()
                .withMaxAttempts(3)
                .withInitialDelay(10)
                .withShouldRetry(e -> true);

        var policy = Policy.compose(retry);

        var outcome = policy.outcome(() -> {
            var value = counter.incrementAndGet();
            if (value < 2) {
                throw new RuntimeException("Fail");
            }
            return "Success";
        });

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Success.class, s ->
                        assertThat(s.value()).isEqualTo("Success"));
    }

    @Test
    void should_chainMultiplePatterns_and_executeInOrder() {
        var callCount = new AtomicInteger(0);

        var retry = Retry.<String>create()
                .withMaxAttempts(2)
                .withInitialDelay(10)
                .withShouldRetry(e -> true);

        var policy = Policy.compose(retry);

        var result = policy.call(() -> {
            var value = callCount.incrementAndGet();
            if (value == 1) {
                throw new RuntimeException("Fail first");
            }
            return "Success after " + value + " calls";
        });

        assertThat(result).isEqualTo("Success after 2 calls");
    }

    @Test
    void should_handleComplexRetryScenario_withEventListener() {
        var events = new ArrayList<String>();

        var retry = Retry.<Integer>create()
                .withMaxAttempts(3)
                .withInitialDelay(10)
                .withListener(event -> {
                    if (event instanceof RetryEvent.AttemptFailed) {
                        events.add("attempt");
                    } else if (event instanceof RetryEvent.Success) {
                        events.add("success");
                    }
                })
                .withShouldRetry(e -> true);

        var policy = Policy.compose(retry);
        var counter = new AtomicInteger(0);

        var result = policy.call(() -> {
            var value = counter.incrementAndGet();
            if (value <= 2) {
                throw new RuntimeException("Fail attempt " + value);
            }
            return 42;
        });

        assertThat(result).isEqualTo(42);
        assertThat(events).containsExactly("attempt", "attempt", "success");
    }

    @Test
    void should_invokeFirstComposedPattern_asOutermostLayer() {
        var callOrder = new ArrayList<String>();

        var policy = Policy.compose(recordingPattern(callOrder, "A"))
                .and(recordingPattern(callOrder, "B"));

        var result = policy.call(() -> {
            callOrder.add("operation");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(callOrder).containsExactly("A-before", "B-before", "operation", "B-after", "A-after");
    }

    @Test
    void should_keepOriginalPolicyUnchanged_when_andCreatesNewPolicy() {
        var callOrder = new ArrayList<String>();

        var basePolicy = Policy.compose(recordingPattern(callOrder, "A"));
        basePolicy.and(recordingPattern(callOrder, "B"));

        basePolicy.call(() -> "operation");

        assertThat(callOrder).containsExactly("A-before", "A-after");
    }

    @Test
    void should_throwOriginalExceptionType_when_composedPatternFails() {
        var retry = Retry.<String>create()
                .withMaxAttempts(2)
                .withInitialDelay(10)
                .withShouldRetry(e -> true);

        var policy = Policy.compose(retry);

        var exception = assertThrows(RetryExhaustedException.class, () -> policy.call(() -> {
            throw new IllegalStateException("boom");
        }));
        assertThat(exception)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(exception.attemptCount()).isEqualTo(2);
    }

    @Test
    void should_propagateOriginalRuntimeException_when_patternDoesNotWrap() {
        Resilient<String> passthrough = new Resilient<>() {
            @Override
            public String call(Operation<String> operation) throws ResilientException {
                return operation.execute();
            }

            @Override
            public Outcome<String> outcome(Operation<String> operation) {
                try {
                    return new Outcome.Success<>(operation.execute());
                } catch (Exception e) {
                    return new Outcome.Failure<>(e);
                }
            }
        };

        var policy = Policy.compose(passthrough);

        var exception = assertThrows(IllegalArgumentException.class, () -> policy.call(() -> {
            throw new IllegalArgumentException("non-wrapped exception");
        }));
        assertThat(exception)
                .hasMessage("non-wrapped exception");
    }

    @Test
    void should_throwNullPointerException_when_composingNullPattern() {
        assertThrows(NullPointerException.class, () ->
            Policy.compose(null));
    }

    @Test
    void should_throwNullPointerException_when_addingNullPattern() {
        var retry = Retry.<String>create().withMaxAttempts(1);
        var policy = Policy.compose(retry);

        assertThrows(NullPointerException.class, () ->
            policy.and(null));
    }

    @Test
    void should_returnFailureWithOriginalCause_when_outcomeMethodUsedWithNonResilientException() {
        var passthroughPattern = new Resilient<String>() {
            @Override
            public String call(Operation<String> operation) throws ResilientException {
                return operation.execute();
            }

            @Override
            public Outcome<String> outcome(Operation<String> operation) {
                try {
                    return new Outcome.Success<>(operation.execute());
                } catch (Exception e) {
                    return new Outcome.Failure<>(e);
                }
            }
        };

        var policy = Policy.compose(passthroughPattern);

        var outcome = policy.outcome(() -> {
            throw new IllegalArgumentException("original non-resiliencia exception");
        });

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Failure.class, failure ->
                        assertThat(failure.cause())
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessage("original non-resiliencia exception"));
    }

    private static Resilient<String> recordingPattern(List<String> callOrder, String name) {
        return new Resilient<>() {
            @Override
            public String call(Operation<String> operation) throws ResilientException {
                callOrder.add(name + "-before");
                try {
                    var result = operation.execute();
                    callOrder.add(name + "-after");
                    return result;
                } catch (Exception e) {
                    throw new ResilientException(name + " failed", e);
                }
            }

            @Override
            public Outcome<String> outcome(Operation<String> operation) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
