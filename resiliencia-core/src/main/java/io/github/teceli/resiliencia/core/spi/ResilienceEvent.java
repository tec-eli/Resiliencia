package io.github.teceli.resiliencia.core.spi;

import java.time.Instant;

/**
 * Base interface for all resilience events.
 * Patterns emit these for observability — metrics, logging, etc. can subscribe.
 */
public interface ResilienceEvent {
    Instant timestamp();

    String patternName();

    /**
     * Listener for consuming events from patterns.
     */
    @FunctionalInterface
    interface Listener {
        void onEvent(ResilienceEvent event);
    }
}
