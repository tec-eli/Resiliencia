package io.github.teceli.resiliencia.metrics.timeout;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TimeoutCountersTest {

    @Test
    void should_exposeNameAndElapsed_when_succeededConstructed() {
        var succeeded = new TimeoutCounters.Succeeded("myTimeout", Duration.ofMillis(100));

        assertThat(succeeded.name()).isEqualTo("myTimeout");
        assertThat(succeeded.elapsed()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void should_exposeNameAndCause_when_failedConstructed() {
        var failed = new TimeoutCounters.Failed("myTimeout", "RuntimeException");

        assertThat(failed.name()).isEqualTo("myTimeout");
        assertThat(failed.cause()).isEqualTo("RuntimeException");
    }

    @Test
    void should_exposeName_when_timedOutConstructed() {
        var timedOut = new TimeoutCounters.TimedOut("myTimeout");

        assertThat(timedOut.name()).isEqualTo("myTimeout");
    }

    @Test
    void should_exposeNameAndOutcome_when_abandonedConstructed() {
        var abandoned = new TimeoutCounters.Abandoned("myTimeout", TimeoutCounters.AbandonedOutcome.SUCCEEDED);

        assertThat(abandoned.name()).isEqualTo("myTimeout");
        assertThat(abandoned.outcome()).isEqualTo(TimeoutCounters.AbandonedOutcome.SUCCEEDED);
    }

    @Test
    void should_declareBothOutcomes_when_abandonedOutcomeEnumInspected() {
        assertThat(TimeoutCounters.AbandonedOutcome.values())
            .containsExactly(TimeoutCounters.AbandonedOutcome.SUCCEEDED, TimeoutCounters.AbandonedOutcome.FAILED);
    }

    @Test
    void should_beEqual_when_sameNameAndElapsed() {
        var first = new TimeoutCounters.Succeeded("myTimeout", Duration.ofMillis(50));
        var second = new TimeoutCounters.Succeeded("myTimeout", Duration.ofMillis(50));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }
}
