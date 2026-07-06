package io.github.teceli.resiliencia.patterns.timeout;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.ResilienciaException;
import io.github.teceli.resiliencia.core.api.ResilienciaTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        var exception = assertThrows(ResilienciaTimeoutException.class, () ->
            timeout.call(TimeoutPatternTest::blockUntilInterrupted));
        assertThat(exception.timeout()).isEqualTo(SHORT_TIMEOUT);
    }

    @Test
    void should_interruptOperation_when_deadlinePasses() throws InterruptedException {
        var interrupted = new CountDownLatch(1);
        var timeout = Timeout.<String>of(SHORT_TIMEOUT);

        assertThrows(ResilienciaTimeoutException.class, () -> timeout.call(() -> {
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
    void should_defaultCancelOnTimeoutToTrue_when_constructed() {
        assertThat(Timeout.<String>of(GENEROUS_TIMEOUT).cancelOnTimeout()).isTrue();
    }

    @Test
    void should_notInterruptOperation_when_cancelOnTimeoutIsFalse() throws Exception {
        var interrupted = new CountDownLatch(1);
        var completedNaturally = new CountDownLatch(1);
        var timeout = Timeout.<String>of(SHORT_TIMEOUT).withCancelOnTimeout(false);

        assertThrows(ResilienciaTimeoutException.class, () -> timeout.call(() -> {
            try {
                Thread.sleep(Duration.ofMillis(200));
                completedNaturally.countDown();
                return "finished naturally";
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new ResilienciaException("interrupted", e);
            }
        }));

        assertThat(completedNaturally.await(5, TimeUnit.SECONDS))
                .as("operation should be allowed to finish naturally when cancelOnTimeout is false")
                .isTrue();
        assertThat(interrupted.getCount())
                .as("worker thread should never have been interrupted")
                .isEqualTo(1);
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

        var exception = assertThrows(IllegalStateException.class, () ->
            timeout.call(() -> {
                throw boom;
            }));
        assertThat(exception).isSameAs(boom);
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
    void should_emitSucceededEvent_when_operationCompletesWithinTimeout() {
        var events = new ArrayList<TimeoutEvent>();
        var timeout = Timeout.<String>of(GENEROUS_TIMEOUT)
                .withListener(event -> events.add((TimeoutEvent) event));

        timeout.call(() -> "done");

        assertThat(events)
                .singleElement()
                .isInstanceOf(TimeoutEvent.Succeeded.class);
    }

    @Test
    void should_emitTimedOutEvent_when_deadlinePasses() {
        // Synchronized: the abandoned worker may append its own Abandoned* event concurrently,
        // asynchronously with respect to this thread, once it reacts to the interrupt.
        var events = Collections.synchronizedList(new ArrayList<TimeoutEvent>());
        var timeout = Timeout.<String>of(SHORT_TIMEOUT)
                .withListener(event -> events.add((TimeoutEvent) event));

        timeout.outcome(TimeoutPatternTest::blockUntilInterrupted);

        assertThat(events)
                .filteredOn(TimeoutEvent.TimedOut.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(TimeoutEvent.TimedOut.class, t ->
                        assertThat(t.timeout()).isEqualTo(SHORT_TIMEOUT));
    }

    @Test
    void should_emitAbandonedWorkerFailedEvent_when_interruptedWorkerEventuallyThrows() throws InterruptedException {
        var events = Collections.synchronizedList(new ArrayList<TimeoutEvent>());
        var workerDone = new CountDownLatch(1);
        var timeout = Timeout.<String>of(SHORT_TIMEOUT)
                .withListener(event -> {
                    events.add((TimeoutEvent) event);
                    if (event instanceof TimeoutEvent.AbandonedWorkerFailed) {
                        workerDone.countDown();
                    }
                });

        timeout.outcome(TimeoutPatternTest::blockUntilInterrupted);

        assertThat(workerDone.await(5, TimeUnit.SECONDS))
                .as("abandoned worker should eventually react to the interrupt and emit its event")
                .isTrue();
        assertThat(events)
                .filteredOn(TimeoutEvent.AbandonedWorkerFailed.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(TimeoutEvent.AbandonedWorkerFailed.class, f ->
                        assertThat(f.cause()).isInstanceOf(ResilienciaException.class));
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
    void should_returnOperationResult_when_listenerThrowsException() {
        var timeout = Timeout.<String>of(GENEROUS_TIMEOUT)
                .withListener(event -> {
                    throw new IllegalStateException("listener boom");
                });

        assertThat(timeout.call(() -> "done")).isEqualTo("done");
    }

    @Test
    void should_reportTimeoutKind_when_patternKindQueried() {
        assertThat(Timeout.<String>of(GENEROUS_TIMEOUT).patternKind()).isEqualTo(PatternKind.TIMEOUT);
        assertThat(Timeout.<String>of(GENEROUS_TIMEOUT).patternName()).isEqualTo("timeout");
    }

    @Test
    void should_throwNullPointerException_when_timeoutIsNull() {
        assertThrows(NullPointerException.class, () ->
            Timeout.<String>of(null));
    }

    @Test
    void should_throwIllegalArgumentException_when_timeoutIsNotPositive() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> Timeout.<String>of(Duration.ZERO));
        assertThatIllegalArgumentException()
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

    @Test
    void should_returnNewInstanceWithCancelOnTimeoutFalse_when_witherCalled() {
        var original = Timeout.<String>of(SHORT_TIMEOUT);

        var reconfigured = original.withCancelOnTimeout(false);

        assertThat(reconfigured).isNotSameAs(original);
        assertThat(original.cancelOnTimeout()).isTrue();
        assertThat(reconfigured.cancelOnTimeout()).isFalse();
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
