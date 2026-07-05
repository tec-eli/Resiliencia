package io.github.teceli.resiliencia.test;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class FakeResilientTest {

    @Test
    void should_executeOperationAndCountCalls_when_called() {
        var fake = FakeResilient.<String>passthrough();

        var result = fake.call(() -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(fake.callCount()).isEqualTo(1);
    }

    @Test
    void should_returnFailureOutcome_when_operationThrows() {
        var fake = FakeResilient.<String>passthrough();
        var boom = new IllegalStateException("boom");

        var outcome = fake.outcome(() -> {
            throw boom;
        });

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Failure.class, f ->
                        assertThat(f.cause()).isSameAs(boom));
    }

    @Test
    void should_impersonateConfiguredPattern_when_kindAndNameOverridden() {
        var fake = FakeResilient.<String>passthrough()
                .withPatternKind(PatternKind.CIRCUIT_BREAKER)
                .withPatternName("circuit-breaker");

        assertThat(fake.patternKind()).isEqualTo(PatternKind.CIRCUIT_BREAKER);
        assertThat(fake.patternName()).isEqualTo("circuit-breaker");
    }

    @Test
    void should_runHookBeforeOperation_when_onCallConfigured() {
        var order = new ArrayList<String>();
        var fake = FakeResilient.<String>passthrough()
                .withOnCall(() -> order.add("hook"));

        fake.call(() -> {
            order.add("operation");
            return "done";
        });

        assertThat(order).containsExactly("hook", "operation");
    }

    @Test
    void should_startWithFreshCallCount_when_witherCalled() {
        var original = FakeResilient.<String>passthrough();
        original.call(() -> "counted on original");

        var reconfigured = original.withPatternName("other");

        assertThat(reconfigured.callCount()).isZero();
        assertThat(original.callCount()).isEqualTo(1);
    }
}
