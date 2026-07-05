package io.github.teceli.resiliencia.test;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilienciaException;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pass-through {@link Resilient} for testing code that composes or accepts patterns
 * (e.g. Policy chains) without pulling in real pattern behavior. Executes the operation
 * directly, counts invocations, and can impersonate any {@link PatternKind} / pattern name.
 *
 * Configured via {@code withX} copy methods like the real patterns; each returns a new
 * instance with a fresh call count.
 */
public final class FakeResilient<T> implements Resilient<T> {

    private final String patternName;
    private final PatternKind patternKind;
    private final Runnable onCall;
    private final AtomicInteger callCount = new AtomicInteger();

    private FakeResilient(String patternName, PatternKind patternKind, Runnable onCall) {
        this.patternName = Objects.requireNonNull(patternName, "patternName must not be null");
        this.patternKind = Objects.requireNonNull(patternKind, "patternKind must not be null");
        this.onCall = Objects.requireNonNull(onCall, "onCall must not be null");
    }

    /**
     * A fake that simply executes the operation, reporting itself as a CUSTOM pattern.
     */
    public static <T> FakeResilient<T> passthrough() {
        return new FakeResilient<>("fake", PatternKind.CUSTOM, () -> { });
    }

    public FakeResilient<T> withPatternName(String patternName) {
        return new FakeResilient<>(patternName, patternKind, onCall);
    }

    public FakeResilient<T> withPatternKind(PatternKind patternKind) {
        return new FakeResilient<>(patternName, patternKind, onCall);
    }

    /**
     * Run the given hook each time this fake executes, before the operation — useful for
     * recording execution order across a chain of fakes.
     */
    public FakeResilient<T> withOnCall(Runnable onCall) {
        return new FakeResilient<>(patternName, patternKind, onCall);
    }

    /**
     * How many times {@link #call} or {@link #outcome} has executed on this instance.
     */
    public int callCount() {
        return callCount.get();
    }

    @Override
    public String patternName() {
        return patternName;
    }

    @Override
    public PatternKind patternKind() {
        return patternKind;
    }

    @Override
    public T call(Operation<T> operation) throws ResilienciaException {
        Objects.requireNonNull(operation, "operation must not be null");
        callCount.incrementAndGet();
        onCall.run();
        return operation.execute();
    }

    @Override
    public Outcome<T> outcome(Operation<T> operation) {
        try {
            return new Outcome.Success<>(call(operation));
        } catch (RuntimeException e) {
            return new Outcome.Failure<>(e);
        }
    }
}
