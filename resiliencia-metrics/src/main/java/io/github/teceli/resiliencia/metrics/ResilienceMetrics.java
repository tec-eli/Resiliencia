package io.github.teceli.resiliencia.metrics;

/**
 * Backend-facing contract that concrete metrics implementations (Micrometer, OpenTelemetry, custom)
 * must implement to observe pattern and Policy events as metrics.
 *
 * <p><strong>Implementation contract:</strong>
 * <ul>
 *   <li><strong>Non-blocking:</strong> No I/O, no lock acquisition that could wait.
 *   <li><strong>Allocation-light:</strong> Avoid unnecessary object creation on the hot path.
 *   <li><strong>Virtual-thread-safe:</strong> Must not pin virtual threads in code resiliencia itself
 *       writes. This applies to the event→metric mapping code, not to any concrete backend registry
 *       the consuming application wires in at runtime.
 * </ul>
 *
 * <p>Each backend implementation performs its own exhaustive {@code switch} over the sealed
 * {@link Snapshot} and {@link Counters} hierarchies inside its {@code observe(...)} method bodies,
 * guaranteed to compile only when all cases are handled.
 */
public interface ResilienceMetrics {
  /**
   * Observe a gauge-worthy snapshot of live state from CircuitBreaker, Bulkhead, or RateLimiter.
   *
   * @param snapshot the snapshot to observe; never null
   */
  void observe(Snapshot snapshot);

  /**
   * Observe a counter/timer-worthy occurrence from any pattern (Retry, Timeout, CircuitBreaker,
   * Bulkhead, RateLimiter) or from Policy's order validation.
   *
   * @param counters the counter event to observe; never null
   */
  void observe(Counters counters);
}
