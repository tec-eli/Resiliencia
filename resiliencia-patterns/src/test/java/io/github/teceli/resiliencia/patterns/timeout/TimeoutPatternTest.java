package io.github.teceli.resiliencia.patterns.timeout;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.ResilienciaException;
import io.github.teceli.resiliencia.core.api.ResilienciaTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for the Timeout pattern: virtual-thread execution, interruption on deadline,
 * outcome mapping, and event emission.
 */
class TimeoutPatternTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(50);
    private static final Duration GENEROUS_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void should_returnValue_when_operationCompletesWithinTimeout() {
        var timeout = Timeout.<String>of(GENEROUS_TIMEOUT);

        var result = timeout.call(() -> "done");

        assertThat(result).isEqualTo("done");
    }

    @Test
    void should_throwResilienciaTimeoutException_when_operationExceedsTimeout() {
        var timeout = Timeout.<String>of(SHORT_TIMEOUT);

        assertThatExceptionOfType(ResilienciaTimeoutException.class)
                .isThrownBy(() -> timeout.call(TimeoutPatternTest::blockUntilInterrupted))
                .extracting(ResilienciaTimeoutException::timeout)
                .isEqualTo(SHORT_TIMEOUT);
    }

    @Test
    void should_interruptOperation_when_deadlinePasses() throws InterruptedException {
        var interrupted = new CountDownLatch(1);
        var timeout = Timeout.<String>of(SHORT_TIMEOUT);

        assertThatExceptionOfType(ResilienciaTimeoutException.class)
                .isThrownBy(() -> timeout.call(() -> {
                    try {
                        Thread.sleep(Duration.ofSeconds(30));
                        return "never";
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new ResilienciaException("interrupted", e);
                    }
                }));

        assertThat(interrupted.await(5, TimeUnit.SECONDS))
                .as("worker thread should be interrupted when the deadline passes")
                .isTrue();
    }

    @Test
    void should_returnTimedOutOutcome_when_usingOutcomeMethod() {
        var timeout = Timeout.<String>of(SHORT_TIMEOUT);

        var outcome = timeout.outcome(TimeoutPatternTest::blockUntilInterrupted);

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.TimedOut.class, t ->
                        assertThat(t.timeout()).isEqualTo(SHORT_TIMEOUT));
    }

    @Test
    void should_rethrowOriginalException_when_operationFailsBeforeDeadline() {
        var timeout = Timeout.<String>of(GENEROUS_TIMEOUT);
        var boom = new IllegalStateException("boom");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> timeout.call(() -> {
                    throw boom;
                }))
                .isSameAs(boom);
    }

    @Test
    void should_returnFailureOutcome_when_operationFailsBeforeDeadline() {
        var timeout = Timeout.<String>of(GENEROUS_TIMEOUT);
        var boom = new IllegalStateException("boom");

        var outcome = timeout.outcome(() -> {
            throw boom;
        });

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Failure.class, f ->
                        assertThat(f.cause()).isSameAs(boom));
    }

    @Test
    void should_emitSuccessEvent_when_operationCompletesWithinTimeout() {
        var events = new ArrayList<TimeoutEvent>();
        var timeout = Timeout.<String>of(GENEROUS_TIMEOUT)
                .withListener(event -> events.add((TimeoutEvent) event));

        timeout.call(() -> "done");

        assertThat(events)
                .singleElement()
                .isInstanceOf(TimeoutEvent.Success.class);
    }

    @Test
    void should_emitTimedOutEvent_when_deadlinePasses() {
        var events = new ArrayList<TimeoutEvent>();
        var timeout = Timeout.<String>of(SHORT_TIMEOUT)
                .withListener(event -> events.add((TimeoutEvent) event));

        timeout.outcome(TimeoutPatternTest::blockUntilInterrupted);

        assertThat(events)
                .singleElement()
                .isInstanceOfSatisfying(TimeoutEvent.TimedOut.class, t ->
                        assertThat(t.timeout()).isEqualTo(SHORT_TIMEOUT));
    }

    @Test
    void should_emitFailedEvent_when_operationFailsBeforeDeadline() {
        var events = new ArrayList<TimeoutEvent>();
        var boom = new IllegalStateException("boom");
        var timeout = Timeout.<String>of(GENEROUS_TIMEOUT)
                .withListener(event -> events.add((TimeoutEvent) event));

        timeout.outcome(() -> {
            throw boom;
        });

        assertThat(events)
                .singleElement()
                .isInstanceOfSatisfying(TimeoutEvent.Failed.class, f ->
                        assertThat(f.error()).isSameAs(boom));
    }

    @Test
    void should_reportTimeoutKind_when_patternKindQueried() {
        assertThat(Timeout.<String>of(GENEROUS_TIMEOUT).patternKind()).isEqualTo(PatternKind.TIMEOUT);
        assertThat(Timeout.<String>of(GENEROUS_TIMEOUT).patternName()).isEqualTo("timeout");
    }

    @Test
    void should_throwNullPointerException_when_timeoutIsNull() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> Timeout.<String>of(null));
    }

    @Test
    void should_throwIllegalArgumentException_when_timeoutIsNotPositive() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Timeout.<String>of(Duration.ZERO));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Timeout.<String>of(Duration.ofMillis(-1)));
    }

    @Test
    void should_returnNewInstance_when_witherCalled() {
        var original = Timeout.<String>of(SHORT_TIMEOUT);

        var reconfigured = original.withTimeout(GENEROUS_TIMEOUT);

        assertThat(reconfigured).isNotSameAs(original);
        assertThat(original.timeout()).isEqualTo(SHORT_TIMEOUT);
        assertThat(reconfigured.timeout()).isEqualTo(GENEROUS_TIMEOUT);
    }

    /**
     * Blocks until interrupted by the timeout; converts the interrupt into an unchecked
     * exception as any interruption-aware operation would.
     */
    private static String blockUntilInterrupted() {
        try {
            Thread.sleep(Duration.ofSeconds(30));
            return "never";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResilienciaException("interrupted", e);
        }
    }
}
