package io.github.teceli.resiliencia.examples;

import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreaker;
import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreakerEvent;
import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreakerOpenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Example: CircuitBreaker lifecycle, Closed → Open → HalfOpen → Closed.
 *
 * <ol>
 *   <li>Failures fill the sliding window and open the circuit</li>
 *   <li>Open circuit rejects calls without running them</li>
 *   <li>After the wait, test calls succeed and the circuit closes again</li>
 * </ol>
 */
public final class CircuitBreakerExample {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerExample.class);

    private static final int WINDOW_SIZE = 4;
    private static final int HALF_OPEN_CALLS = 2;
    private static final Duration OPEN_WAIT = Duration.ofMillis(200);

    private CircuitBreakerExample() {
    }

    public static void main(String[] args) {
        log.info("=== CircuitBreaker ===");

        var breaker = CircuitBreaker.<String>of("downstream")
                .withSlidingWindowSize(WINDOW_SIZE)
                .withFailureRateThreshold(0.5)
                .withWaitDurationInOpenState(OPEN_WAIT)
                .withPermittedCallsInHalfOpenState(HALF_OPEN_CALLS)
                .withRecordOn(List.of(IllegalStateException.class))
                .withListener(event -> {
                    if (event instanceof CircuitBreakerEvent.Opened opened) {
                        log.info("Circuit opened: {}", opened.reason());
                    } else if (event instanceof CircuitBreakerEvent.HalfOpened) {
                        log.info("Circuit half-open: allowing test calls");
                    } else if (event instanceof CircuitBreakerEvent.Closed) {
                        log.info("Circuit closed: downstream recovered");
                    }
                });

        exampleOpensOnFailures(breaker);
        exampleRejectsWhileOpen(breaker);
        exampleRecovers(breaker);
    }

    static void exampleOpensOnFailures(CircuitBreaker<String> breaker) {
        log.info("Example 1: Failures open the circuit");

        for (var i = 1; i <= WINDOW_SIZE; i++) {
            var outcome = breaker.outcome(() -> {
                throw new IllegalStateException("downstream unavailable");
            });
            log.info("Call {} -> {}", i, outcome);
        }
        log.info("State: {}", breaker.state());
    }

    static void exampleRejectsWhileOpen(CircuitBreaker<String> breaker) {
        log.info("Example 2: Open circuit rejects calls");

        try {
            breaker.call(() -> "never runs");
        } catch (CircuitBreakerOpenException e) {
            log.warn("Rejected: {}", e.getMessage());
        }
    }

    static void exampleRecovers(CircuitBreaker<String> breaker) {
        log.info("Example 3: Recovery after the wait duration");

        sleep(OPEN_WAIT.plusMillis(100));

        for (var i = 1; i <= HALF_OPEN_CALLS; i++) {
            log.info("Test call {} -> {}", i, breaker.call(() -> "ok"));
        }
        log.info("State: {}", breaker.state());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the circuit", e);
        }
    }
}
