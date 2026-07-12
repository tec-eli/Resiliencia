package io.github.teceli.resiliencia.metrics;

/**
 * A no-op {@link ResilienceMetrics} implementation that discards all recordings. Used by default
 * when no backend is configured, to avoid null-checking or forcing consumers to provide a metrics
 * implementation they may not need.
 *
 * <p>Thread-safe and lock-free. Suitable for use as a default or when metrics are deliberately
 * disabled.
 */
public final class NoOpMetrics implements ResilienceMetrics {
  /**
   * Singleton instance of the no-op metrics implementation.
   */
  public static final NoOpMetrics INSTANCE = new NoOpMetrics();

  private NoOpMetrics() {}

  @Override
  public void observe(Snapshot snapshot) {
    // No-op: discard
  }

  @Override
  public void observe(Counters counters) {
    // No-op: discard
  }
}
