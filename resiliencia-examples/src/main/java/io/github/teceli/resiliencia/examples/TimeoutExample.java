package io.github.teceli.resiliencia.examples;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.ResilientTimeoutException;
import io.github.teceli.resiliencia.patterns.timeout.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Example: Timeout bounds how long the caller waits for an operation.
 *
 * <ol>
 *   <li>Operation finishes within the deadline</li>
 *   <li>Operation exceeds the deadline: {@link ResilientTimeoutException}</li>
 *   <li>Same failure without exceptions, via {@code outcome()}</li>
 * </ol>
 */
public final class TimeoutExample {
    private static final Logger log = LoggerFactory.getLogger(TimeoutExample.class);

    private static final Duration DEADLINE = Duration.ofMillis(200);

    private TimeoutExample() {
    }

    public static void main(String[] args) {
        log.info("=== Timeout ===");
        exampleWithinDeadline();
        exampleTimesOut();
        exampleOutcome();
    }

    static void exampleWithinDeadline() {
        log.info("Example 1: Operation within the deadline");

        var timeout = Timeout.<String>of("fast-call", DEADLINE);

        var result = timeout.call(() -> slowOperation(Duration.ofMillis(20)));

        log.info("Result: {}", result);
    }

    static void exampleTimesOut() {
        log.info("Example 2: Operation exceeds the deadline");

        var timeout = Timeout.<String>of("slow-call", DEADLINE);

        try {
            timeout.call(() -> slowOperation(Duration.ofSeconds(2)));
        } catch (ResilientTimeoutException e) {
            log.warn("Timed out after {}", e.timeout());
        }
    }

    static void exampleOutcome() {
        log.info("Example 3: Outcome instead of exceptions");

        var timeout = Timeout.<String>of("outcome-call", DEADLINE);

        var outcome = timeout.outcome(() -> slowOperation(Duration.ofSeconds(2)));

        switch (outcome) {
            case Outcome.Success<String> success -> log.info("Success: {}", success.value());
            case Outcome.Failure<String> failure -> log.warn("Failure: {}", failure.cause().getMessage());
            case Outcome.TimedOut<String> timedOut -> log.warn("Timed out after {}", timedOut.timeout());
        }
    }

    // Responds to interruption so the worker stops once the Timeout cancels it
    private static String slowOperation(Duration duration) {
        try {
            Thread.sleep(duration);
            return "done after " + duration.toMillis() + " ms";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Operation interrupted", e);
        }
    }
}
