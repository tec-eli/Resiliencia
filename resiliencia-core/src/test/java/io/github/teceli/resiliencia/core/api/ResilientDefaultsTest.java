package io.github.teceli.resiliencia.core.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Resilient}'s default methods: the fallback values a user-defined
 * implementation gets for free without overriding anything.
 */
class ResilientDefaultsTest {

    @Test
    void should_defaultPatternNameToCustom_when_notOverridden() {
        assertThat(minimal().patternName()).isEqualTo("custom");
    }

    @Test
    void should_defaultPatternKindToCustom_when_notOverridden() {
        assertThat(minimal().patternKind()).isEqualTo(PatternKind.CUSTOM);
    }

    @Test
    void should_defaultHasOwnDeadlineToFalse_when_notOverridden() {
        assertThat(minimal().hasOwnDeadline()).isFalse();
    }

    private static Resilient<String> minimal() {
        return new Resilient<>() {
            @Override
            public String call(Operation<String> operation) throws ResilientException {
                return operation.execute();
            }

            @Override
            public Outcome<String> outcome(Operation<String> operation) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
