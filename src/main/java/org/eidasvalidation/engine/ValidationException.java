package org.eidasvalidation.engine;

/** The document could not be validated at all (unreadable, not a supported format, engine failure). */
public class ValidationException extends RuntimeException {

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
