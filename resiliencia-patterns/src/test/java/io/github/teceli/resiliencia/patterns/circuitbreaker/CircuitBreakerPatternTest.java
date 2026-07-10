package io.github.teceli.resiliencia.patterns.circuitbreaker;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.spi.Clock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the CircuitBreaker pattern: state machine transitions (Closed/Open/HalfOpen),
 * failure- and slow-call-rate thresholds, exception filtering, permit limiting in HalfOpen, and
 * event emission.
 */
class CircuitBreakerPatternTest {

    private static final Duration WAIT_DURATION = Duration.ofSeconds(30);
    private static final Duration SLOW_THRESHOLD = Duration.ofMillis(100);

    @Test
    void should_returnValue_when_circuitIsClosed() {
        var circuitBreaker = CircuitBreaker.<String>of("test");

        var result = circuitBreaker.call(() -> "done");

        assertThat(result).isEqualTo("done");
    }

    @Test
    void should_notOpenCircuit_when_slidingWindowNotYetFull() {
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withSlidingWindowSize(4)
                .withFailureRateThreshold(0.5);

        assertThrows(IllegalStateException.class, () ->
            circuitBreaker.call(CircuitBreakerPatternTest::boom));
        assertThrows(IllegalStateException.class, () ->
            circuitBreaker.call(CircuitBreakerPatternTest::boom));

        // Window holds only 2/4 calls: thresholds are not evaluated yet, the operation still runs.
        assertThat(circuitBreaker.call(() -> "still closed")).isEqualTo("still closed");
    }

    @Test
    void should_openCircuit_when_failureRateThresholdExceeded() {
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withSlidingWindowSize(4)
                .withFailureRateThreshold(0.5);

        circuitBreaker.outcome(() -> "ok");
        circuitBreaker.outcome(() -> "ok");
        circuitBreaker.outcome(CircuitBreakerPatternTest::boom);
        circuitBreaker.outcome(CircuitBreakerPatternTest::boom);

        var executed = new AtomicInteger(0);
        assertThrows(CircuitBreakerOpenException.class, () -> circuitBreaker.call(() -> {
            executed.incrementAndGet();
            return "rejected";
        }));
        assertThat(executed.get()).isZero();
    }

    @Test
    void should_openCircuit_when_slowCallRateThresholdExceeded() {
        var clock = new ManualClock();
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(1.0)
                .withSlowCallRateThreshold(0.5)
                .withSlowCallDurationThreshold(SLOW_THRESHOLD)
                .withClock(clock);

        circuitBreaker.outcome(() -> {
            clock.advance(SLOW_THRESHOLD.plusMillis(50));
            return "slow";
        });
        circuitBreaker.outcome(() -> "fast");

        assertThrows(CircuitBreakerOpenException.class, () ->
            circuitBreaker.call(() -> "rejected"));
    }

    @Test
    void should_notTreatCallsAsSlow_when_slowCallDurationThresholdNotConfigured() {
        var clock = new ManualClock();
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(1.0)
                .withSlowCallRateThreshold(0.5)
                .withClock(clock);

        circuitBreaker.outcome(() -> {
            clock.advance(Duration.ofSeconds(10));
            return "slow but unmeasured";
        });
        circuitBreaker.outcome(() -> {
            clock.advance(Duration.ofSeconds(10));
            return "slow but unmeasured";
        });

        assertThat(circuitBreaker.call(() -> "still closed")).isEqualTo("still closed");
    }

    @Test
    void should_rejectCallsImmediately_when_circuitIsOpen() {
        var circuitBreaker = openedCircuitBreaker(new ManualClock());
        var executed = new AtomicInteger(0);

        var outcome = circuitBreaker.outcome(() -> {
            executed.incrementAndGet();
            return "rejected";
        });

        assertThat(executed.get()).isZero();
        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Failure.class, f ->
                        assertThat(f.cause()).isInstanceOf(CircuitBreakerOpenException.class));
    }

    @Test
    void should_includeNameInExceptionMessage_when_circuitIsOpen() {
        var circuitBreaker = openedCircuitBreaker(new ManualClock(), "payments");

        var exception = assertThrows(CircuitBreakerOpenException.class, () ->
            circuitBreaker.call(() -> "rejected"));
        assertThat(exception).hasMessageContaining("payments");
    }

    @Test
    void should_transitionToHalfOpen_when_waitDurationElapses() {
        var clock = new ManualClock();
        var circuitBreaker = openedCircuitBreaker(clock);

        clock.advance(WAIT_DURATION);

        assertThat(circuitBreaker.call(() -> "test call")).isEqualTo("test call");
    }

    @Test
    void should_closeCircuit_when_allHalfOpenTestCallsSucceed() {
        var clock = new ManualClock();
        var events = new ArrayList<CircuitBreakerEvent>();
        var circuitBreaker = openCircuit(baseCircuitBreaker(clock)
                .withListener(event -> events.add((CircuitBreakerEvent) event)));

        clock.advance(WAIT_DURATION);
        circuitBreaker.call(() -> "test call 1");
        circuitBreaker.call(() -> "test call 2");
        circuitBreaker.call(() -> "test call 3");

        assertThat(events)
                .filteredOn(CircuitBreakerEvent.Closed.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(CircuitBreakerEvent.Closed.class, closed ->
                        assertThat(closed.numberOfSuccessfulTestCalls()).isEqualTo(3));
    }

    @Test
    void should_reopenCircuit_when_halfOpenTestCallFails() {
        var clock = new ManualClock();
        var circuitBreaker = openedCircuitBreaker(clock);
        clock.advance(WAIT_DURATION);

        assertThrows(IllegalStateException.class, () ->
            circuitBreaker.call(CircuitBreakerPatternTest::boom));

        var executed = new AtomicInteger(0);
        assertThrows(CircuitBreakerOpenException.class, () -> circuitBreaker.call(() -> {
            executed.incrementAndGet();
            return "rejected again";
        }));
        assertThat(executed.get()).isZero();
    }

    @Test
    void should_limitTestCalls_when_moreArriveThanPermittedInHalfOpen() throws Exception {
        var clock = new ManualClock();
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(0.5)
                .withWaitDurationInOpenState(WAIT_DURATION)
                .withPermittedCallsInHalfOpenState(1)
                .withClock(clock);
        circuitBreaker.outcome(CircuitBreakerPatternTest::boom);
        circuitBreaker.outcome(CircuitBreakerPatternTest::boom);
        clock.advance(WAIT_DURATION);

        var insideOperation = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holderOutcome = new AtomicReference<Outcome<String>>();
        var holder = Thread.ofVirtual().start(() -> holderOutcome.set(circuitBreaker.outcome(() -> {
            insideOperation.countDown();
            awaitQuietly(releaseHolder);
            return "half-open test call";
        })));
        assertThat(insideOperation.await(5, TimeUnit.SECONDS)).isTrue();

        var extraExecuted = new AtomicInteger(0);
        var extraOutcome = circuitBreaker.outcome(() -> {
            extraExecuted.incrementAndGet();
            return "should not run";
        });

        releaseHolder.countDown();
        holder.join(Duration.ofSeconds(5));

        assertThat(extraExecuted.get()).isZero();
        assertThat(extraOutcome)
                .isInstanceOfSatisfying(Outcome.Failure.class, f ->
                        assertThat(f.cause()).isInstanceOf(CircuitBreakerOpenException.class));
        assertThat(holderOutcome.get())
                .isInstanceOfSatisfying(Outcome.Success.class, s ->
                        assertThat(s.value()).isEqualTo("half-open test call"));
    }

    @Test
    void should_rethrowOriginalException_when_operationFails() {
        var circuitBreaker = CircuitBreaker.<String>of("test");
        var boom = new IllegalStateException("boom");

        var exception = assertThrows(IllegalStateException.class, () ->
            circuitBreaker.call(() -> {
                throw boom;
            }));
        assertThat(exception).isSameAs(boom);
    }

    @Test
    void should_returnFailureWithCircuitBreakerOpenException_when_usingOutcomeMethod() {
        var circuitBreaker = openedCircuitBreaker(new ManualClock());

        var outcome = circuitBreaker.outcome(() -> "rejected");

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Failure.class, f ->
                        assertThat(f.cause()).isInstanceOf(CircuitBreakerOpenException.class));
    }

    @Test
    void should_notRecordAsFailure_when_exceptionTypeIsIgnored() {
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(0.5)
                .withIgnoreOn(List.of(IllegalStateException.class));

        assertThrows(IllegalStateException.class, () ->
            circuitBreaker.call(CircuitBreakerPatternTest::boom));
        assertThrows(IllegalStateException.class, () ->
            circuitBreaker.call(CircuitBreakerPatternTest::boom));

        // Both calls were ignored for rate purposes even though the window is now full.
        assertThat(circuitBreaker.call(() -> "still closed")).isEqualTo("still closed");
    }

    @Test
    void should_onlyCountConfiguredTypes_when_recordOnConfigured() {
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(0.5)
                .withRecordOn(List.of(IllegalStateException.class));

        // Not in recordOn: rethrown to the caller, but not counted against the window.
        assertThrows(IllegalArgumentException.class, () ->
            circuitBreaker.call(() -> {
                throw new IllegalArgumentException("wrong type");
            }));
        assertThrows(IllegalArgumentException.class, () ->
            circuitBreaker.call(() -> {
                throw new IllegalArgumentException("wrong type");
            }));

        // Window is full of non-counted entries; one matching failure now tips the 50% rate.
        assertThrows(IllegalStateException.class, () ->
            circuitBreaker.call(CircuitBreakerPatternTest::boom));

        assertThrows(CircuitBreakerOpenException.class, () ->
            circuitBreaker.call(() -> "rejected"));
    }

    @Test
    void should_ignoreOnTakePrecedence_when_typeMatchesBothRecordOnAndIgnoreOn() {
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(0.5)
                .withRecordOn(List.of(IllegalStateException.class))
                .withIgnoreOn(List.of(IllegalStateException.class));

        assertThrows(IllegalStateException.class, () ->
            circuitBreaker.call(CircuitBreakerPatternTest::boom));
        assertThrows(IllegalStateException.class, () ->
            circuitBreaker.call(CircuitBreakerPatternTest::boom));

        assertThat(circuitBreaker.call(() -> "still closed")).isEqualTo("still closed");
    }

    @Test
    void should_recordAsFailure_when_recordOnResultMatches() {
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(0.5)
                .withRecordOnResult("bad"::equals);

        assertThat(circuitBreaker.call(() -> "bad")).isEqualTo("bad");
        assertThat(circuitBreaker.call(() -> "bad")).isEqualTo("bad");

        assertThrows(CircuitBreakerOpenException.class, () ->
            circuitBreaker.call(() -> "rejected"));
    }

    @Test
    void should_returnSuccess_when_recordOnResultThrows() {
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withRecordOnResult(result -> {
                    throw new RuntimeException("boom");
                });

        var outcome = circuitBreaker.outcome(() -> "ok");

        assertThat(outcome).isEqualTo(new Outcome.Success<>("ok"));
    }

    @Test
    void should_notOpenCircuit_when_recordOnResultThrowsOnEverySuccess() {
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(0.5)
                .withRecordOnResult(result -> {
                    throw new RuntimeException("boom");
                });

        assertThat(circuitBreaker.call(() -> "ok")).isEqualTo("ok");
        assertThat(circuitBreaker.call(() -> "ok")).isEqualTo("ok");

        // A throwing recordOnResult is treated as "false" (not a failure), so the circuit stays Closed.
        assertThat(circuitBreaker.call(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void should_emitCallRecordedEvent_when_callSucceeds() {
        var events = new ArrayList<CircuitBreakerEvent>();
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withListener(event -> events.add((CircuitBreakerEvent) event));

        circuitBreaker.call(() -> "done");

        assertThat(events)
                .singleElement()
                .isInstanceOfSatisfying(CircuitBreakerEvent.CallRecorded.class, recorded -> {
                    assertThat(recorded.name()).isEqualTo("test");
                    assertThat(recorded.isSuccessful()).isTrue();
                    assertThat(recorded.currentFailureRate()).isZero();
                });
    }

    @Test
    void should_emitOpenedEvent_when_failureRateThresholdExceeded() {
        var events = new ArrayList<CircuitBreakerEvent>();
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(0.5)
                .withListener(event -> events.add((CircuitBreakerEvent) event));

        circuitBreaker.outcome(CircuitBreakerPatternTest::boom);
        circuitBreaker.outcome(CircuitBreakerPatternTest::boom);

        assertThat(events)
                .filteredOn(CircuitBreakerEvent.Opened.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(CircuitBreakerEvent.Opened.class, opened -> {
                    assertThat(opened.name()).isEqualTo("test");
                    assertThat(opened.reason()).isEqualTo(CircuitBreakerEvent.Reason.FAILURE_RATE_EXCEEDED);
                });
    }

    @Test
    void should_emitOpenedEvent_when_slowCallRateThresholdExceeded() {
        var clock = new ManualClock();
        var events = new ArrayList<CircuitBreakerEvent>();
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(1.0)
                .withSlowCallRateThreshold(0.5)
                .withSlowCallDurationThreshold(SLOW_THRESHOLD)
                .withClock(clock)
                .withListener(event -> events.add((CircuitBreakerEvent) event));

        circuitBreaker.outcome(() -> {
            clock.advance(SLOW_THRESHOLD.plusMillis(50));
            return "slow";
        });
        circuitBreaker.outcome(() -> "fast");

        assertThat(events)
                .filteredOn(CircuitBreakerEvent.Opened.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(CircuitBreakerEvent.Opened.class, opened ->
                        assertThat(opened.reason()).isEqualTo(CircuitBreakerEvent.Reason.SLOW_CALL_RATE_EXCEEDED));
    }

    @Test
    void should_emitHalfOpenedEvent_when_waitDurationElapses() {
        var clock = new ManualClock();
        var events = new ArrayList<CircuitBreakerEvent>();
        var circuitBreaker = openCircuit(baseCircuitBreaker(clock)
                .withListener(event -> events.add((CircuitBreakerEvent) event)));

        clock.advance(WAIT_DURATION);
        circuitBreaker.call(() -> "test call");

        assertThat(events)
                .filteredOn(CircuitBreakerEvent.HalfOpened.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(CircuitBreakerEvent.HalfOpened.class, halfOpened ->
                        assertThat(halfOpened.name()).isEqualTo("test"));
    }

    @Test
    void should_emitOpenedEvent_when_halfOpenTestCallFails() {
        var clock = new ManualClock();
        var events = new ArrayList<CircuitBreakerEvent>();
        var circuitBreaker = openCircuit(baseCircuitBreaker(clock)
                .withListener(event -> events.add((CircuitBreakerEvent) event)));
        clock.advance(WAIT_DURATION);

        assertThrows(IllegalStateException.class, () ->
            circuitBreaker.call(CircuitBreakerPatternTest::boom));

        assertThat(events)
                .filteredOn(CircuitBreakerEvent.Opened.class::isInstance)
                .hasSize(2); // once for the initial rate breach, once for the failed test call
    }

    @Test
    void should_returnOperationResult_when_listenerThrowsException() {
        var circuitBreaker = CircuitBreaker.<String>of("test")
                .withListener(event -> {
                    throw new IllegalStateException("listener boom");
                });

        assertThat(circuitBreaker.call(() -> "done")).isEqualTo("done");
    }

    @Test
    void should_resolveHalfOpenPermitAndReopenCircuit_when_testCallThrowsError() {
        var clock = new ManualClock();
        var circuitBreaker = openedCircuitBreaker(clock);
        clock.advance(WAIT_DURATION);

        assertThrows(TestError.class, () -> circuitBreaker.outcome(() -> {
            throw new TestError();
        }));

        // The permit consumed by the Error's test call was resolved instead of leaking: the
        // circuit reopens, exactly as it would for a normal failed test call.
        assertThat(circuitBreaker.state()).isInstanceOf(CircuitState.Open.class);
    }

    @Test
    void should_throwNullPointerException_when_listenerIsNull() {
        assertThrows(NullPointerException.class, () ->
            CircuitBreaker.<String>of("test").withListener(null));
    }

    @Test
    void should_reportCircuitBreakerKind_when_patternKindQueried() {
        assertThat(CircuitBreaker.<String>of("test").patternKind()).isEqualTo(PatternKind.CIRCUIT_BREAKER);
        assertThat(CircuitBreaker.<String>of("test").patternName()).isEqualTo("circuit-breaker");
    }

    @Test
    void should_createIndependentInstanceWithFreshState_when_witherCalled() {
        var original = openedCircuitBreaker(new ManualClock());

        var reconfigured = original.withFailureRateThreshold(0.9);

        assertThat(reconfigured).isNotSameAs(original);
        assertThat(reconfigured.call(() -> "independent")).isEqualTo("independent");
        assertThrows(CircuitBreakerOpenException.class, () ->
            original.call(() -> "still open"));
    }

    @Test
    void should_rejectInvalidConfiguration_when_constructed() {
        assertThrows(NullPointerException.class, () ->
            CircuitBreaker.of(null));

        var cb1 = CircuitBreaker.<String>of("test");
        assertThrows(IllegalArgumentException.class, () ->
            cb1.withFailureRateThreshold(0.0));
        assertThrows(IllegalArgumentException.class, () ->
            cb1.withFailureRateThreshold(1.1));
        assertThrows(IllegalArgumentException.class, () ->
            cb1.withSlowCallRateThreshold(0.0));
        assertThrows(IllegalArgumentException.class, () ->
            cb1.withSlowCallRateThreshold(1.1));
        assertThrows(IllegalArgumentException.class, () ->
            cb1.withSlidingWindowSize(0));
        assertThrows(IllegalArgumentException.class, () ->
            cb1.withPermittedCallsInHalfOpenState(0));

        var cb2 = CircuitBreaker.<String>of("test");
        assertThrows(NullPointerException.class, () ->
            cb2.withWaitDurationInOpenState(null));
        assertThrows(NullPointerException.class, () ->
            cb2.withRecordOn(null));
        assertThrows(NullPointerException.class, () ->
            cb2.withIgnoreOn(null));
        assertThrows(NullPointerException.class, () ->
            cb2.withRecordOnResult(null));
        assertThrows(NullPointerException.class, () ->
            cb2.withClock(null));
    }

    @Test
    void should_throwIllegalArgumentException_when_openStateHasNegativeRemainingWait() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CircuitState.Open(Instant.now(), Duration.ofMillis(-1)));
    }

    @Test
    void should_reportClosedState_when_noCallsHaveBeenRecorded() {
        var circuitBreaker = CircuitBreaker.<String>of("test");

        assertThat(circuitBreaker.state()).isEqualTo(new CircuitState.Closed());
    }

    @Test
    void should_reportOpenStateWithDecreasingRemainingWait_when_circuitIsOpen() {
        var clock = new ManualClock();
        var openedAt = clock.instant();
        var circuitBreaker = openedCircuitBreaker(clock);

        assertThat(circuitBreaker.state())
                .isInstanceOfSatisfying(CircuitState.Open.class, open -> {
                    assertThat(open.openedAt()).isEqualTo(openedAt);
                    assertThat(open.remainingWait()).isEqualTo(WAIT_DURATION);
                });

        clock.advance(WAIT_DURATION.dividedBy(2));

        assertThat(circuitBreaker.state())
                .isInstanceOfSatisfying(CircuitState.Open.class, open ->
                        assertThat(open.remainingWait()).isEqualTo(WAIT_DURATION.dividedBy(2)));
    }

    @Test
    void should_reportHalfOpenState_when_waitDurationElapsesBeforeNextCall() {
        var clock = new ManualClock();
        var circuitBreaker = openedCircuitBreaker(clock);
        clock.advance(WAIT_DURATION);

        circuitBreaker.call(() -> "test call");

        assertThat(circuitBreaker.state()).isEqualTo(new CircuitState.HalfOpen(1,1));
    }

    @Test
    void should_carryNameOpenSinceAndRemainingWait_when_rejectedWhileOpen() {
        var clock = new ManualClock();
        var openedAt = clock.instant();
        var circuitBreaker = openedCircuitBreaker(clock, "payments");
        clock.advance(WAIT_DURATION.dividedBy(2));

        var outcome = circuitBreaker.outcome(() -> "rejected");

        assertThat(outcome)
                .isInstanceOfSatisfying(Outcome.Failure.class, f ->
                        assertThat(f.cause()).isInstanceOfSatisfying(CircuitBreakerOpenException.class, ex -> {
                            assertThat(ex.name()).isEqualTo("payments");
                            assertThat(ex.openSince()).contains(openedAt);
                            assertThat(ex.remainingWait()).contains(WAIT_DURATION.dividedBy(2));
                        }));
    }

    @Test
    void should_carryNameButNoOpenSinceOrRemainingWait_when_rejectedWhileHalfOpenPermitsExhausted()
            throws Exception {
        var clock = new ManualClock();
        var circuitBreaker = CircuitBreaker.<String>of("halfopen-test")
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(0.5)
                .withWaitDurationInOpenState(WAIT_DURATION)
                .withPermittedCallsInHalfOpenState(1)
                .withClock(clock);
        circuitBreaker.outcome(CircuitBreakerPatternTest::boom);
        circuitBreaker.outcome(CircuitBreakerPatternTest::boom);
        clock.advance(WAIT_DURATION);

        var insideOperation = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holder = Thread.ofVirtual().start(() -> circuitBreaker.outcome(() -> {
            insideOperation.countDown();
            awaitQuietly(releaseHolder);
            return "half-open test call";
        }));
        assertThat(insideOperation.await(5, TimeUnit.SECONDS)).isTrue();

        var extraOutcome = circuitBreaker.outcome(() -> "should not run");

        releaseHolder.countDown();
        holder.join(Duration.ofSeconds(5));

        assertThat(extraOutcome)
                .isInstanceOfSatisfying(Outcome.Failure.class, f ->
                        assertThat(f.cause()).isInstanceOfSatisfying(CircuitBreakerOpenException.class, ex -> {
                            assertThat(ex.name()).isEqualTo("halfopen-test");
                            assertThat(ex.openSince()).isEmpty();
                            assertThat(ex.remainingWait()).isEmpty();
                        }));
    }

    @Test
    void should_admitAtLeastPermittedCalls_when_permitsExhaustedUnderContention() throws Exception {
        var clock = new ManualClock();
        final var permittedCalls = 3;
        var circuitBreaker = CircuitBreaker.<String>of("contention-test")
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(0.5)
                .withWaitDurationInOpenState(WAIT_DURATION)
                .withPermittedCallsInHalfOpenState(permittedCalls)
                .withClock(clock);
        circuitBreaker.outcome(CircuitBreakerPatternTest::boom);
        circuitBreaker.outcome(CircuitBreakerPatternTest::boom);
        clock.advance(WAIT_DURATION);

        // Spawn many threads trying to call simultaneously
        final var threadCount = 10;
        var allReady = new CountDownLatch(threadCount);
        var allDone = new CountDownLatch(threadCount);
        var outcomes = new AtomicReference<Outcome<String>[]>(new Outcome[threadCount]);
        var outcomesArray = outcomes.get();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            Thread.ofVirtual().start(() -> {
                try {
                    allReady.countDown();
                    allReady.await(); // Synchronize all threads to maximize contention
                    outcomesArray[index] = circuitBreaker.outcome(() -> "test-" + index);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcomesArray[index] = new Outcome.Failure<>(new IllegalStateException("interrupted", e));
                } finally {
                    allDone.countDown();
                }
            });
        }

        assertThat(allDone.await(5, TimeUnit.SECONDS)).isTrue();

        // Count successes and failures
        var successCount = 0;
        var rejectedCount = 0;
        for (var outcome : outcomesArray) {
            if (outcome instanceof Outcome.Success<?>) {
                successCount++;
            } else if (outcome instanceof Outcome.Failure<?> f &&
                    f.cause() instanceof CircuitBreakerOpenException) {
                rejectedCount++;
            }
        }

        // The CAS-capped HalfOpen admission loop guarantees at least permittedCalls are admitted.
        // Stragglers that make their first admission check only after the circuit has already
        // closed race against unconditional Closed admission instead: there is no synchronization
        // barrier forcing them to be evaluated against the HalfOpen budget, so more than
        // permittedCalls can succeed under heavy contention. See docs/architecture/patterns/
        // circuit-breaker.md, "HalfOpen admission under concurrent bursts".
        assertThat(successCount).isGreaterThanOrEqualTo(permittedCalls);
        assertThat(successCount + rejectedCount).isEqualTo(threadCount);
    }

    /**
     * A fresh, Closed CircuitBreaker configured with a window of size 2 and a 50% failure-rate
     * threshold, using the given clock. Apply further {@code withX} calls (e.g. a listener)
     * before opening it via {@link #openCircuit}, since each {@code withX} call returns an
     * independent instance starting back in the Closed state.
     */
    private static CircuitBreaker<String> baseCircuitBreaker(ManualClock clock) {
        return baseCircuitBreaker(clock, "test");
    }

    private static CircuitBreaker<String> baseCircuitBreaker(ManualClock clock, String name) {
        return CircuitBreaker.<String>of(name)
                .withSlidingWindowSize(2)
                .withFailureRateThreshold(0.5)
                .withWaitDurationInOpenState(WAIT_DURATION)
                .withClock(clock);
    }

    /**
     * Trips the given CircuitBreaker Open via 2 failures, matching the window size and
     * failure-rate threshold set by {@link #baseCircuitBreaker}. Returns the same instance.
     */
    private static CircuitBreaker<String> openCircuit(CircuitBreaker<String> circuitBreaker) {
        circuitBreaker.outcome(CircuitBreakerPatternTest::boom);
        circuitBreaker.outcome(CircuitBreakerPatternTest::boom);
        return circuitBreaker;
    }

    private static CircuitBreaker<String> openedCircuitBreaker(ManualClock clock) {
        return openCircuit(baseCircuitBreaker(clock));
    }

    private static CircuitBreaker<String> openedCircuitBreaker(ManualClock clock, String name) {
        return openCircuit(baseCircuitBreaker(clock, name));
    }

    private static String boom() {
        throw new IllegalStateException("boom");
    }

    private static final class TestError extends Error {}

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /**
     * Deterministic clock for tests: time only moves when advanced explicitly, so wait-duration
     * and slow-call assertions are exact and instant.
     */
    private static final class ManualClock implements Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public synchronized Instant instant() {
            return now;
        }

        @Override
        public synchronized void sleep(long millis) {
            now = now.plusMillis(millis);
        }

        synchronized void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
