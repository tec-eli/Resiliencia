package io.github.teceli.resiliencia.test;

import io.github.teceli.resiliencia.metrics.Counters;
import io.github.teceli.resiliencia.metrics.ResilienceMetrics;
import io.github.teceli.resiliencia.metrics.Snapshot;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fake implementation of {@link ResilienceMetrics} for testing code that consumes metrics.
 * <p>
 * Captures all {@link Snapshot} and {@link Counters} emissions in thread-safe, queryable
 * lists. Provides methods to interrogate captured metrics by type, introspect their values,
 * and reset state between test runs.
 * </p>
 * <p>
 * <strong>Thread-safety:</strong> All method calls are thread-safe. Safe to use from multiple
 * virtual threads or platform threads simultaneously, including concurrent calls to
 * {@code record(...)} and getter methods.
 * </p>
 */
public final class FakeResilienceMetrics implements ResilienceMetrics {
    private final CopyOnWriteArrayList<Snapshot> snapshots = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Counters> counters = new CopyOnWriteArrayList<>();

    @Override
    public void observe(Snapshot snapshot) {
        snapshots.add(snapshot);
    }

    @Override
    public void observe(Counters counters) {
        this.counters.add(counters);
    }

    /**
     * Returns an immutable view of all captured snapshots in emission order.
     *
     * @return an immutable list, never null
     */
    public List<Snapshot> getAllSnapshots() {
        return List.copyOf(snapshots);
    }

    /**
     * Returns an immutable view of all captured counters in emission order.
     *
     * @return an immutable list, never null
     */
    public List<Counters> getAllCounters() {
        return List.copyOf(counters);
    }

    /**
     * Returns all captured snapshots of a specific type.
     * <p>
     * Performs an {@code instanceof} filter and cast. Useful for filtering to a single
     * pattern's gauge emissions (e.g. {@code CircuitBreakerSnapshot.class}).
     * </p>
     *
     * @param type the snapshot interface or record type to filter by
     * @param <T> the snapshot type
     * @return an immutable list of matching snapshots in emission order, never null
     */
    public <T extends Snapshot> List<T> getSnapshotsOfType(Class<T> type) {
        return snapshots.stream()
            .filter(type::isInstance)
            .map(type::cast)
            .toList();
    }

    /**
     * Returns all captured counters of a specific type.
     * <p>
     * Performs an {@code instanceof} filter and cast. Useful for filtering to a single
     * pattern's counter emissions (e.g. {@code RetryCounters.class}).
     * </p>
     *
     * @param type the counters interface or record type to filter by
     * @param <T> the counters type
     * @return an immutable list of matching counters in emission order, never null
     */
    public <T extends Counters> List<T> getCountersOfType(Class<T> type) {
        return counters.stream()
            .filter(type::isInstance)
            .map(type::cast)
            .toList();
    }

    /**
     * Returns the number of captured snapshots.
     *
     * @return the snapshot count
     */
    public int snapshotCount() {
        return snapshots.size();
    }

    /**
     * Returns the number of captured counters.
     *
     * @return the counters count
     */
    public int countersCount() {
        return counters.size();
    }

    /**
     * Clears all captured snapshots and counters.
     * <p>
     * Useful for resetting state between test cases or after assertions.
     * </p>
     */
    public void reset() {
        snapshots.clear();
        counters.clear();
    }

    /**
     * Returns true if no snapshots or counters have been captured.
     *
     * @return true if empty, false otherwise
     */
    public boolean isEmpty() {
        return snapshots.isEmpty() && counters.isEmpty();
    }
}
