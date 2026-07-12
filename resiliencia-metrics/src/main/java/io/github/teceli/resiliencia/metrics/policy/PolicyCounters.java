package io.github.teceli.resiliencia.metrics.policy;

import io.github.teceli.resiliencia.core.api.PatternKind;
import io.github.teceli.resiliencia.metrics.Counters;

/**
 * Counter-worthy occurrences emitted by Policy's order validation.
 */
public sealed interface PolicyCounters extends Counters {

    record ValidationWarning(PatternKind outer, PatternKind inner) implements PolicyCounters {
    }
}
