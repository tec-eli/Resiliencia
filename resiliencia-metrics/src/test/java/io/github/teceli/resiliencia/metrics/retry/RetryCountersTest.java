package io.github.teceli.resiliencia.metrics.retry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryCountersTest {

    @Test
    void should_exposeNameAndCause_when_attemptFailedConstructed() {
        var attemptFailed = new RetryCounters.AttemptFailed("myRetry", "RuntimeException");

        assertThat(attemptFailed.name()).isEqualTo("myRetry");
        assertThat(attemptFailed.cause()).isEqualTo("RuntimeException");
    }

    @Test
    void should_allowNullCause_when_causeTaggingDisabled() {
        var attemptFailed = new RetryCounters.AttemptFailed("myRetry", null);

        assertThat(attemptFailed.cause()).isNull();
    }

    @Test
    void should_exposeNameAndTotalAttempts_when_successConstructed() {
        var success = new RetryCounters.Success("myRetry", 3);

        assertThat(success.name()).isEqualTo("myRetry");
        assertThat(success.totalAttempts()).isEqualTo(3);
    }

    @Test
    void should_exposeNameAndCause_when_exhaustedConstructed() {
        var exhausted = new RetryCounters.Exhausted("myRetry", "IllegalStateException");

        assertThat(exhausted.name()).isEqualTo("myRetry");
        assertThat(exhausted.cause()).isEqualTo("IllegalStateException");
    }

    @Test
    void should_exposeNameAndCause_when_rejectedConstructed() {
        var rejected = new RetryCounters.Rejected("myRetry", "other");

        assertThat(rejected.name()).isEqualTo("myRetry");
        assertThat(rejected.cause()).isEqualTo("other");
    }

    @Test
    void should_exposeNameAndCause_when_interruptedConstructed() {
        var interrupted = new RetryCounters.Interrupted("myRetry", "other");

        assertThat(interrupted.name()).isEqualTo("myRetry");
        assertThat(interrupted.cause()).isEqualTo("other");
    }

    @Test
    void should_beEqual_when_sameNameAndCause() {
        var first = new RetryCounters.AttemptFailed("myRetry", "other");
        var second = new RetryCounters.AttemptFailed("myRetry", "other");

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }
}
