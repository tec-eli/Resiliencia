package io.github.teceli.resiliencia.core.api;

import java.io.Serial;
import java.util.Objects;

/**
 * Base exception for all resiliencia errors. Extends RuntimeException (unchecked).
 * Users can catch this for any library-related failure, or catch specific subtypes.
 */
public class ResilientException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ResilientException(String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
    }

    public ResilientException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"),
                Objects.requireNonNull(cause, "cause must not be null"));
    }
}
