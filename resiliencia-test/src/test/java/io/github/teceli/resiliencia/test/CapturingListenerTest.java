package io.github.teceli.resiliencia.test;

import io.github.teceli.resiliencia.core.spi.ResilienceEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CapturingListenerTest {

    @Test
    void should_captureEventsInArrivalOrder_when_multipleEventsReceived() {
        var listener = new CapturingListener();
        var first = new TestEventA(Instant.parse("2026-01-01T00:00:00Z"));
        var second = new TestEventB(Instant.parse("2026-01-01T00:00:01Z"));

        listener.onEvent(first);
        listener.onEvent(second);

        assertThat(listener.events()).containsExactly(first, second);
        assertThat(listener.count()).isEqualTo(2);
    }

    @Test
    void should_filterEventsByType_when_eventsOfTypeCalled() {
        var listener = new CapturingListener();
        var eventA = new TestEventA(Instant.now());
        var eventB = new TestEventB(Instant.now());

        listener.onEvent(eventA);
        listener.onEvent(eventB);

        assertThat(listener.eventsOfType(TestEventA.class)).containsExactly(eventA);
        assertThat(listener.eventsOfType(TestEventB.class)).containsExactly(eventB);
    }

    @Test
    void should_returnSnapshot_when_eventsCalledAfterFurtherEmission() {
        var listener = new CapturingListener();
        listener.onEvent(new TestEventA(Instant.now()));

        var snapshot = listener.events();
        listener.onEvent(new TestEventA(Instant.now()));

        assertThat(snapshot).hasSize(1);
        assertThat(listener.events()).hasSize(2);
    }

    @Test
    void should_captureAllEvents_when_calledConcurrently() throws InterruptedException {
        var listener = new CapturingListener();
        var threadCount = 20;
        var ready = new CountDownLatch(threadCount);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threadCount);
        var errors = new AtomicInteger();

        IntStream.range(0, threadCount).forEach(i -> Thread.ofVirtual().start(() -> {
            ready.countDown();
            try {
                start.await();
                listener.onEvent(new TestEventA(Instant.now()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.incrementAndGet();
            } finally {
                done.countDown();
            }
        }));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(errors.get()).isZero();
        assertThat(listener.count()).isEqualTo(threadCount);
    }

    private record TestEventA(Instant timestamp) implements ResilienceEvent {
        @Override
        public String patternName() {
            return "test-a";
        }
    }

    private record TestEventB(Instant timestamp) implements ResilienceEvent {
        @Override
        public String patternName() {
            return "test-b";
        }
    }
}
