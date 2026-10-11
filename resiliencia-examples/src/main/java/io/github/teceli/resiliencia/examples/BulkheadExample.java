package io.github.teceli.resiliencia.examples;

import io.github.teceli.resiliencia.patterns.bulkhead.Bulkhead;
import io.github.teceli.resiliencia.patterns.bulkhead.BulkheadFullException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

/**
 * Example: Bulkhead caps concurrent calls and rejects the excess.
 *
 * <ol>
 *   <li>One call holds the only permit</li>
 *   <li>A concurrent call is rejected immediately with {@link BulkheadFullException}</li>
 *   <li>Once the permit is released, calls are admitted again</li>
 * </ol>
 */
public final class BulkheadExample {
    private static final Logger log = LoggerFactory.getLogger(BulkheadExample.class);

    private BulkheadExample() {
    }

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        log.info("=== Bulkhead ===");

        var bulkhead = Bulkhead.<String>of("inventory-service", 1);
        var permitHeld = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        // Example 1: the first call occupies the single permit on its own virtual thread
        log.info("Example 1: A call holds the only permit");
        var holder = bulkhead.callAsync(() -> {
            permitHeld.countDown();
            awaitQuietly(release);
            return "holder finished";
        });
        permitHeld.await();

        // Example 2: the concurrent call is rejected right away
        log.info("Example 2: A concurrent call is rejected");
        try {
            bulkhead.call(() -> "never runs");
        } catch (BulkheadFullException e) {
            log.warn("Rejected: {}", e.getMessage());
        }

        // Example 3: after release the bulkhead admits calls again
        log.info("Example 3: Permit released, calls admitted again");
        release.countDown();
        log.info("Holder result: {}", holder.get());
        log.info("New call result: {}", bulkhead.call(() -> "admitted"));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the permit", e);
        }
    }
}
