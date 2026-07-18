package io.github.teceli.resiliencia.metrics.policy;

import io.github.teceli.resiliencia.core.api.PatternKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyCountersTest {

    @Test
    void should_exposeOuterAndInnerPatternKind_when_validationWarningConstructed() {
        var warning = new PolicyCounters.ValidationWarning(PatternKind.RETRY, PatternKind.CIRCUIT_BREAKER);

        assertThat(warning.outer()).isEqualTo(PatternKind.RETRY);
        assertThat(warning.inner()).isEqualTo(PatternKind.CIRCUIT_BREAKER);
    }

    @Test
    void should_beEqual_when_sameOuterAndInner() {
        var first = new PolicyCounters.ValidationWarning(PatternKind.TIMEOUT, PatternKind.RETRY);
        var second = new PolicyCounters.ValidationWarning(PatternKind.TIMEOUT, PatternKind.RETRY);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void should_notBeEqual_when_outerAndInnerSwapped() {
        var outerRetry = new PolicyCounters.ValidationWarning(PatternKind.RETRY, PatternKind.TIMEOUT);
        var outerTimeout = new PolicyCounters.ValidationWarning(PatternKind.TIMEOUT, PatternKind.RETRY);

        assertThat(outerRetry).isNotEqualTo(outerTimeout);
    }
}
