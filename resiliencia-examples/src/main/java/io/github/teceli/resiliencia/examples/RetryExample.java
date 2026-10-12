package io.github.teceli.resiliencia.examples;

import io.github.teceli.resiliencia.compose.Policy;
import io.github.teceli.resiliencia.core.api.ResilientException;
import io.github.teceli.resiliencia.patterns.retry.Retry;
import io.github.teceli.resiliencia.patterns.retry.RetryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example: Retry composed into a Policy and executed synchronously.
 *
 * <ol>
 *   <li>Success after a transient failure</li>
 *   <li>Retry exhaustion</li>
 *   <li>Observing retry events through a listener</li>
 * </ol>
 */
public final class RetryExample {
    private static final Logger log = LoggerFactory.getLogger(RetryExample.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_DELAY_MS = 50;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private static final String RESULT = "Result: {}";
    private static final String TOTAL_ATTEMPTS = "Total attempts: {}";
    private static final String FAILURE = "Failure: {}";

    private RetryExample() {
    }

    public static void main(String[] args) {
        log.info("=== Retry ===");
        exampleSuccessAfterRetry();
        exampleRetryExhaustion();
        exampleWithEventListener();
    }

    static void exampleSuccessAfterRetry() {
        log.info("Example 1: Success after retry");

        var attempts = new AtomicInteger();

        // The default Retry only retries IOException; this example fails with an unchecked exception
        var retry = Retry.<String>create("success-after-retry")
                .withMaxAttempts(MAX_ATTEMPTS)
                .withInitialDelay(INITIAL_DELAY_MS)
                .withBackoffMultiplier(BACKOFF_MULTIPLIER)
                .withShouldRetry(error -> true);

        var policy = Policy.compose(retry);

        try {
            var result = policy.call(() -> {
                var attempt = attempts.incrementAndGet();
                if (attempt < 2) {
                    throw new IllegalStateException("Simulated failure on attempt " + attempt);
                }
                return "Success on attempt " + attempt;
            });
            log.info(RESULT, result);
            log.info(TOTAL_ATTEMPTS, attempts.get());
        } catch (ResilientException e) {
            log.warn(FAILURE, e.getMessage());
        }
    }

    static void exampleRetryExhaustion() {
        log.info("Example 2: Retry exhaustion");

        var attempts = new AtomicInteger();

        var retry = Retry.<String>create("retry-exhaustion")
                .withMaxAttempts(MAX_ATTEMPTS)
                .withInitialDelay(INITIAL_DELAY_MS)
                .withShouldRetry(error -> true);

        var policy = Policy.compose(retry);

        try {
            var result = policy.call(() -> {
                throw new IllegalStateException("Always fails (attempt " + attempts.incrementAndGet() + ")");
            });
            log.info(RESULT, result);
        } catch (ResilientException e) {
            log.warn(FAILURE, e.getMessage());
            log.info(TOTAL_ATTEMPTS, attempts.get());
        }
    }

    static void exampleWithEventListener() {
        log.info("Example 3: Event listening");

        var attempts = new AtomicInteger();

        var retry = Retry.<String>create("retry-with-listener")
                .withMaxAttempts(MAX_ATTEMPTS)
                .withInitialDelay(INITIAL_DELAY_MS)
                .withShouldRetry(error -> true)
                .withListener(event -> {
                    if (event instanceof RetryEvent.AttemptFailed failed) {
                        log.info("Attempt {} failed: {}", failed.attemptNumber(), failed.error().getMessage());
                    } else if (event instanceof RetryEvent.Success success) {
                        log.info("Succeeded after {} attempts", success.totalAttempts());
                    } else if (event instanceof RetryEvent.Exhausted exhausted) {
                        log.info("Exhausted after {} attempts", exhausted.totalAttempts());
                    }
                });

        var policy = Policy.compose(retry);

        try {
            var result = policy.call(() -> {
                var attempt = attempts.incrementAndGet();
                if (attempt < MAX_ATTEMPTS) {
                    throw new IllegalStateException("Fail attempt " + attempt);
                }
                return "Success!";
            });
            log.info(RESULT, result);
        } catch (ResilientException e) {
            log.warn(FAILURE, e.getMessage());
        }
    }
}
