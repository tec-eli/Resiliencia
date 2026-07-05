package io.github.teceli.resiliencia.test;

import io.github.teceli.resiliencia.core.api.Outcome;

/**
 * Entry point for resiliencia-specific AssertJ assertions.
 *
 * <pre>{@code
 * import static io.github.teceli.resiliencia.test.ResilienciaAssertions.assertThat;
 *
 * assertThat(retry.outcome(op)).isSuccess().hasValue("done");
 * assertThat(timeout.outcome(op)).isTimedOutAfter(Duration.ofMillis(50));
 * }</pre>
 */
public final class ResilienciaAssertions {

    private ResilienciaAssertions() {
    }

    public static <T> OutcomeAssert<T> assertThat(Outcome<T> actual) {
        return new OutcomeAssert<>(actual);
    }
}
