package org.eidasvalidation.service;

import org.eidasvalidation.engine.TrustSummary;
import org.eidasvalidation.engine.ValidationException;
import org.eidasvalidation.i18n.Messages;
import org.eidasvalidation.report.Report;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Something that validates documents against loaded trust data: in this process
 * ({@link ValidationService}) or in a supervised worker process
 * ({@link org.eidasvalidation.sandbox.SandboxedValidator}).
 */
public interface Validator extends AutoCloseable {

    TrustSummary trust();

    /** True when the trusted lists this validator loaded are older than the configured maximum age. */
    boolean isStale();

    /**
     * @throws ValidationException if the document cannot be parsed at all, or (for a sandboxed
     *                             validator) if the worker had to be killed while reading it
     */
    Report validate(Path document, String displayName, Instant at, boolean online, Messages messages);

    @Override
    default void close() {
    }
}
