package io.github.teceli.resiliencia.examples;

import io.github.teceli.resiliencia.compose.Policy;
import io.github.teceli.resiliencia.core.api.ResilientException;
import io.github.teceli.resiliencia.patterns.retry.Retry;
import io.github.teceli.resiliencia.patterns.retry.RetryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example: Policy composition with Retry pattern and synchronous execution.
 *
 * This is the MVP showing:
 * 1. Configure a Retry pattern (max 3 attempts, exponential backoff)
 * 2. Compose it into a Policy
 * 3. Execute synchronously: get result or exception
 * 4. Listen to retry events for observability
 */
public class RetryExample {
    private static final Logger log = LoggerFactory.getLogger(RetryExample.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final int INITIAL_DELAY_MS = 50;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private static final String RESULT = "Result: {}";
    private static final String TOTAL_ATTEMPTS = "Total attempts: {}";
    private static final String EXCEPTION = "Exception: {}";

    public static void main(String[] args) {
        log.info("=== Resiliencia MVP: Policy + Retry + Sync Execution ===");

        // Example 1: Successful operation with retry
        exampleSuccessAfterRetry();

        // Example 2: Operation that exhausts retries
        exampleRetryExhaustion();

        // Example 3: Operation with event listening
        exampleWithEventListener();
    }

    static void exampleSuccessAfterRetry() {
        log.info("Example 1: Success after retry (with custom predicate)");

        var counter = new Object() { int value = 0; };

        // Default Retry only retries IOException; for other exceptions, use withShouldRetry
        var retry = Retry.<String>create("success-after-retry")
                .withMaxAttempts(MAX_ATTEMPTS)
                .withInitialDelay(INITIAL_DELAY_MS)
                .withBackoffMultiplier(BACKOFF_MULTIPLIER)
                .withShouldRetry(e -> true);  // Retry all exceptions for this example

        var policy = Policy.compose(retry);

        try {
            var result = policy.call(() -> {
                counter.value++;
                if (counter.value < 2) {
                    throw new RuntimeException("Simulated failure on attempt " + counter.value);
                }
                return "Success on attempt " + counter.value;
            });
            log.info(RESULT, result);
            log.info(TOTAL_ATTEMPTS, counter.value);
        } catch (ResilientException e) {
            log.warn(EXCEPTION, e.getMessage());
        }
    }

    static void exampleRetryExhaustion() {
        log.info("Example 2: Retry exhaustion");

        var counter = new Object() { int value = 0; };

        var retry = Retry.<String>create("retry-exhaustion")
                .withMaxAttempts(MAX_ATTEMPTS)
                .withInitialDelay(INITIAL_DELAY_MS)
                .withShouldRetry(e -> true);  // Retry all exceptions for this example

        var policy = Policy.compose(retry);

        try {
            var result = policy.call(() -> {
                counter.value++;
                throw new RuntimeException("Always fails (attempt " + counter.value + ")");
            });
            log.info(RESULT, result);
        } catch (ResilientException e) {
            log.warn(EXCEPTION, e.getMessage());
            log.info(TOTAL_ATTEMPTS, counter.value);
        }
    }

    static void exampleWithEventListener() {
        log.info("Example 3: Event listening");

        var counter = new Object() { int value = 0; };

        var retry = Retry.<String>create("retry-with-listener")
                .withMaxAttempts(MAX_ATTEMPTS)
                .withInitialDelay(INITIAL_DELAY_MS)
                .withShouldRetry(e -> true)  // Retry all exceptions for this example
                .withListener(event -> {
                    switch (event) {
                        case RetryEvent.AttemptFailed attempt ->
                                log.debug("Attempt {} failed: {}", attempt.attemptNumber(),
                                        attempt.error().getMessage());
                        case RetryEvent.Success success ->
                                log.debug("Success after {} attempts", success.totalAttempts());
                        case RetryEvent.Exhausted exhausted ->
                                log.debug("Exhausted after {} attempts", exhausted.totalAttempts());
                        default -> {
                        }
                    }
                });

        var policy = Policy.compose(retry);

        try {
            var result = policy.call(() -> {
                counter.value++;
                if (counter.value < 3) {
                    throw new RuntimeException("Fail attempt " + counter.value);
                }
                return "Success!";
            });
            log.info(RESULT, result);
        } catch (ResilientException e) {
            log.warn(EXCEPTION, e.getMessage());
        }
    }
}
