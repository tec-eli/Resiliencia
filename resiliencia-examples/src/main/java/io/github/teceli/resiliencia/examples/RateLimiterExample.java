package io.github.teceli.resiliencia.examples;

import io.github.teceli.resiliencia.patterns.ratelimiter.RateLimiter;
import io.github.teceli.resiliencia.patterns.ratelimiter.RateLimiterEvent;
import io.github.teceli.resiliencia.patterns.ratelimiter.RateLimiterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Example: RateLimiter allows a fixed number of calls per period.
 *
 * <ol>
 *   <li>Calls within the limit are permitted</li>
 *   <li>Calls beyond the limit are rejected with {@link RateLimiterException}</li>
 *   <li>The same rejection without exceptions, via {@code outcome()}</li>
 * </ol>
 */
public final class RateLimiterExample {
    private static final Logger log = LoggerFactory.getLogger(RateLimiterExample.class);

    private static final int LIMIT = 2;
    private static final Duration PERIOD = Duration.ofSeconds(10);

    private RateLimiterExample() {
    }

    public static void main(String[] args) {
        log.info("=== RateLimiter ===");

        var limiter = RateLimiter.<String>of("public-api", LIMIT, PERIOD)
                .withListener(event -> {
                    if (event instanceof RateLimiterEvent.Permitted permitted) {
                        log.info("Permitted, {} permits remaining", permitted.remainingPermits());
                    } else if (event instanceof RateLimiterEvent.Rejected rejected) {
                        log.info("Rejected, estimated wait {}", rejected.estimatedWait());
                    }
                });

        exampleWithinLimit(limiter);
        exampleRejected(limiter);
        exampleOutcome(limiter);
    }

    static void exampleWithinLimit(RateLimiter<String> limiter) {
        log.info("Example 1: Calls within the limit");

        for (var i = 1; i <= LIMIT; i++) {
            log.info("Call {} -> {}", i, limiter.call(() -> "ok"));
        }
    }

    static void exampleRejected(RateLimiter<String> limiter) {
        log.info("Example 2: Call beyond the limit");

        try {
            limiter.call(() -> "never runs");
        } catch (RateLimiterException e) {
            log.warn("Rejected: {}", e.getMessage());
        }
    }

    static void exampleOutcome(RateLimiter<String> limiter) {
        log.info("Example 3: Outcome instead of exceptions");

        var outcome = limiter.outcome(() -> "never runs");

        log.info("Outcome: {}", outcome);
    }
}
