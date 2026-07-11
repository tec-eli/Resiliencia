package io.github.teceli.resiliencia.compose;

import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.core.spi.ResilienceEvent;

import java.time.Instant;

/**
 * Emitted by {@link Policy} whenever a WARN-severity {@code OrderingRule} fires during
 * {@link Policy#and(Resilient)} and construction proceeds — in addition to, not instead of, the
 * existing SLF4J {@code WARN} log line. See {@code docs/architecture/compose/policy.md}'s
 * "Observing WARN-severity warnings" section for the full rationale.
 *
 * ERROR-severity rules ({@link InvalidPolicyException}) never produce this event: construction
 * fails before a {@code Policy} instance exists to attach a listener to.
 *
 * @param outer        the {@link PatternKind} sitting further out in the chain
 * @param inner        the {@link PatternKind} being added, sitting further in
 * @param problem      human-readable description of why this ordering is a footgun
 * @param suggestedFix human-readable suggestion for how to avoid it
 */
public record PolicyValidationWarning(Instant timestamp, PatternKind outer, PatternKind inner, String problem,
                                       String suggestedFix) implements ResilienceEvent {

    @Override
    public String patternName() {
        return "policy";
    }
}
