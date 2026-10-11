package org.eidasvalidation.engine;

/**
 * A signature validation engine. The rest of the tool only talks to this interface so the
 * engine can be swapped or two engines can be run side by side for cross-checking.
 */
public interface ValidationEngine {

    /** Human-readable engine name and version, recorded in every report. */
    String name();

    /**
     * Validates every signature in the document.
     *
     * @throws ValidationException if the document cannot be parsed at all; a parseable document
     *                             without signatures is not an error and yields an empty list
     */
    ValidationReport validate(ValidationRequest request);
}
