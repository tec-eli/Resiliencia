package io.github.teceli.resiliencia.examples;

import io.github.teceli.resiliencia.compose.InvalidPolicyException;
import io.github.teceli.resiliencia.compose.Policy;
import io.github.teceli.resiliencia.core.api.ResilientException;
import io.github.teceli.resiliencia.patterns.circuitbreaker.CircuitBreaker;
import io.github.teceli.resiliencia.patterns.retry.Retry;
import io.github.teceli.resiliencia.patterns.timeout.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example: composing several patterns with Policy.
 *
 * <ol>
 *   <li>Retry around a per-attempt Timeout</li>
 *   <li>{@code useOptimumOrder} sorts patterns into the recommended order</li>
 *   <li>An invalid order (Retry wrapping CircuitBreaker) is rejected at construction</li>
 * </ol>
 */
public final class PolicyExample {
    private static final Logger log = LoggerFactory.getLogger(PolicyExample.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_DELAY_MS = 20;

    private PolicyExample() {
    }

    public static void main(String[] args) {
        log.info("=== Policy ===");
        exampleRetryWithTimeout();
        exampleOptimumOrder();
        exampleInvalidOrder();
    }

    static void exampleRetryWithTimeout() {
        log.info("Example 1: Retry around a per-attempt Timeout");

        var attempts = new AtomicInteger();

        var retry = Retry.<String>create("outer-retry")
                .withMaxAttempts(MAX_ATTEMPTS)
                .withInitialDelay(INITIAL_DELAY_MS)
                .withShouldRetry(error -> true);
        var timeout = Timeout.<String>of("per-attempt", Duration.ofMillis(100));

        // First attempt is too slow and times out; the second one succeeds
        var policy = Policy.compose(retry).and(timeout);

        var result = policy.call(() -> {
            var attempt = attempts.incrementAndGet();
            pause(attempt == 1 ? Duration.ofSeconds(1) : Duration.ofMillis(10));
            return "succeeded on attempt " + attempt;
        });

        log.info("Result: {}", result);
    }

    static void exampleOptimumOrder() {
        log.info("Example 2: useOptimumOrder");

        var retry = Retry.<String>create("retry").withMaxAttempts(MAX_ATTEMPTS).withInitialDelay(INITIAL_DELAY_MS);
        var timeout = Timeout.<String>of("timeout", Duration.ofSeconds(1));
        var breaker = CircuitBreaker.<String>of("breaker");

        // Passed in arbitrary order; the policy runs them as CircuitBreaker -> Retry -> Timeout
        var policy = Policy.useOptimumOrder(timeout, retry, breaker);

        log.info("Result: {}", policy.call(() -> "composed in optimum order"));
    }

    static void exampleInvalidOrder() {
        log.info("Example 3: Invalid order is rejected");

        var retry = Retry.<String>create("retry");
        var breaker = CircuitBreaker.<String>of("breaker");

        try {
            Policy.compose(retry).and(breaker);
        } catch (InvalidPolicyException e) {
            log.warn("Rejected at construction: {}", e.getMessage());
        } catch (ResilientException e) {
            log.error("Unexpected failure: {}", e.getMessage());
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
