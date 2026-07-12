package io.github.teceli.resiliencia.metrics.timeout;

import io.github.teceli.resiliencia.metrics.Counters;

import java.time.Duration;

/**
 * Counter/timer-worthy occurrences emitted by Timeout. Timeout has no live, gauge-worthy state, so
 * it never produces a {@code Snapshot}.
 */
public sealed interface TimeoutCounters extends Counters {

    enum AbandonedOutcome {
        SUCCEEDED,
        FAILED
    }

    record Succeeded(String name, Duration elapsed) implements TimeoutCounters {
    }

    record Failed(String name, String cause) implements TimeoutCounters {
    }

    record TimedOut(String name) implements TimeoutCounters {
    }

    record Abandoned(String name, AbandonedOutcome outcome) implements TimeoutCounters {
    }
}
