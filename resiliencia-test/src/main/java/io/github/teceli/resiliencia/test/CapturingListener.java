package io.github.teceli.resiliencia.test;

import io.github.teceli.resiliencia.core.spi.ResilienceEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link ResilienceEvent.Listener} that records every event it receives, in arrival order,
 * for assertion in tests. Thread-safe: patterns may emit from any calling thread, including
 * concurrently under jcstress-style tests.
 */
public final class CapturingListener implements ResilienceEvent.Listener {

    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void onEvent(ResilienceEvent event) {
        events.add(event);
    }

    /** All events received so far, in arrival order. Snapshot — does not reflect later events. */
    public List<ResilienceEvent> events() {
        return List.copyOf(events);
    }

    /** Events received so far, narrowed to a specific event type. */
    public <T extends ResilienceEvent> List<T> eventsOfType(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    public int count() {
        return events.size();
    }
}
