package io.github.teceli.resiliencia.core.api;

import java.io.Serial;

/**
 * Base exception for all resiliencia errors. Extends RuntimeException (unchecked).
 * Users can catch this for any library-related failure, or catch specific subtypes.
 */
public class ResilientException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ResilientException(String message) {
        super(message);
    }

    public ResilientException(String message, Throwable cause) {
        super(message, cause);
    }
}
