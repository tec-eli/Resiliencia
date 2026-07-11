package io.github.teceli.resiliencia.compose;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilientException;
import io.github.teceli.resiliencia.core.spi.ResilienceEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link Policy#withListener(ResilienceEvent.Listener)} and the
 * {@link PolicyValidationWarning} event it can emit, per
 * {@code docs/architecture/compose/policy.md}'s "Observing WARN-severity warnings" section.
 */
class PolicyListenerTest {

    @Test
    void should_receivePolicyValidationWarning_when_warnSeverityOrderingFires() {
        var timeout = fakePattern(PatternKind.TIMEOUT);
        var retry = fakePattern(PatternKind.RETRY);
        var receivedEvents = new ArrayList<ResilienceEvent>();

        Policy.compose(timeout)
                .withListener(receivedEvents::add)
                .and(retry);

        assertThat(receivedEvents).hasSize(1);
        assertThat(receivedEvents.getFirst()).isInstanceOfSatisfying(PolicyValidationWarning.class, warning -> {
            assertThat(warning.outer()).isEqualTo(PatternKind.TIMEOUT);
            assertThat(warning.inner()).isEqualTo(PatternKind.RETRY);
            assertThat(warning.problem()).contains("Timeout wraps Retry");
            assertThat(warning.suggestedFix()).isNotBlank();
            assertThat(warning.timestamp()).isNotNull();
            assertThat(warning.patternName()).isEqualTo("policy");
        });
    }

    @Test
    void should_notInvokeListener_when_orderingIsValid() {
        var circuitBreaker = fakePattern(PatternKind.CIRCUIT_BREAKER);
        var retry = fakePattern(PatternKind.RETRY);
        var receivedEvents = new ArrayList<ResilienceEvent>();

        Policy.compose(circuitBreaker)
                .withListener(receivedEvents::add)
                .and(retry);

        assertThat(receivedEvents).isEmpty();
    }

    @Test
    void should_notInvokeListener_when_errorSeverityOrderingIsRejected() {
        var retry = fakePattern(PatternKind.RETRY);
        var circuitBreaker = fakePattern(PatternKind.CIRCUIT_BREAKER);
        var receivedEvents = new ArrayList<ResilienceEvent>();

        var policyWithListener = Policy.compose(retry).withListener(receivedEvents::add);

        assertThrows(InvalidPolicyException.class, () -> policyWithListener.and(circuitBreaker));

        assertThat(receivedEvents).isEmpty();
    }

    @Test
    void should_notifyAllListeners_when_multipleListenersAreAttached() {
        var timeout = fakePattern(PatternKind.TIMEOUT);
        var retry = fakePattern(PatternKind.RETRY);
        var firstListenerEvents = new ArrayList<ResilienceEvent>();
        var secondListenerEvents = new ArrayList<ResilienceEvent>();

        Policy.compose(timeout)
                .withListener(firstListenerEvents::add)
                .withListener(secondListenerEvents::add)
                .and(retry);

        assertThat(firstListenerEvents).hasSize(1);
        assertThat(secondListenerEvents).hasSize(1);
        assertThat(firstListenerEvents.getFirst()).isEqualTo(secondListenerEvents.getFirst());
    }

    @Test
    void should_notNotifyListener_when_attachedAfterTheWarningAlreadyFired() {
        // A listener only observes warnings raised by .and() calls made after it was attached.
        var timeout = fakePattern(PatternKind.TIMEOUT);
        var retry = fakePattern(PatternKind.RETRY);
        var receivedEvents = new ArrayList<ResilienceEvent>();

        Policy.compose(timeout)
                .and(retry)
                .withListener(receivedEvents::add);

        assertThat(receivedEvents).isEmpty();
    }

    @Test
    void should_throwNullPointerException_when_withListenerReceivesNull() {
        var policy = Policy.compose(fakePattern(PatternKind.RETRY));

        assertThatNullPointerException().isThrownBy(() -> policy.withListener(null));
    }

    @Test
    void should_notThrow_when_listenerItselfThrows() {
        var timeout = fakePattern(PatternKind.TIMEOUT);
        var retry = fakePattern(PatternKind.RETRY);

        var policyWithBrokenListener = Policy.compose(timeout)
                .withListener(event -> {
                    throw new RuntimeException("broken listener");
                });

        assertThat(policyWithBrokenListener.and(retry).call(() -> "done")).isEqualTo("done");
    }

    /**
     * A pass-through pattern that only reports the given kind — enough for order validation,
     * which never looks at behavior.
     */
    private static Resilient<String> fakePattern(PatternKind kind) {
        return new Resilient<>() {
            @Override
            public String call(Operation<String> operation) throws ResilientException {
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
}
