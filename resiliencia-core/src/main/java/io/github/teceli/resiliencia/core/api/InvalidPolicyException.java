package io.github.teceli.resiliencia.core.api;

import java.io.Serial;

/**
 * Thrown when a Policy is constructed with an invalid configuration (e.g. no patterns).
 * The message describes the problem; {@link #suggestedFix()} describes how to resolve it.
 */
public final class InvalidPolicyException extends ResilienciaException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String suggestedFix;

    public InvalidPolicyException(String problem, String suggestedFix) {
        super(problem);
        this.suggestedFix = suggestedFix;
    }

    /**
     * A human-readable suggestion for how to fix the invalid configuration.
     */
    public String suggestedFix() {
        return suggestedFix;
    }
}
