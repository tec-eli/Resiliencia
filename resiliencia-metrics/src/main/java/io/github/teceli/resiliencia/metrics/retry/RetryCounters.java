package io.github.teceli.resiliencia.metrics.retry;

import io.github.teceli.resiliencia.metrics.Counters;

/**
 * Counter/timer-worthy occurrences emitted by Retry. Retry has no live, gauge-worthy state, so it
 * never produces a {@code Snapshot}.
 */
public sealed interface RetryCounters extends Counters {

    /**
     * One failed attempt, sourced from {@code RetryEvent.AttemptFailed}, which fires once per
     * failed attempt within a call.
     */
    record AttemptFailed(String name, String cause) implements RetryCounters {
    }

    /**
     * The call succeeded — sourced from {@code RetryEvent.Success}, emitted exactly once per call,
     * when the retry loop as a whole succeeds. Distinct from AttemptFailed rather than a shared
     * Outcome enum on one record: the two represent different units — one call vs. one attempt —
     * and Success has no per-attempt equivalent to pair with. totalAttempts is carried on the
     * record for backends that want to build a distribution of attempts-per-call; the default
     * listener does not tag by it.
     */
    record Success(String name, int totalAttempts) implements RetryCounters {
    }

    record Exhausted(String name, String cause) implements RetryCounters {
    }

    record Rejected(String name, String cause) implements RetryCounters {
    }

    record Interrupted(String name, String cause) implements RetryCounters {
    }
}
