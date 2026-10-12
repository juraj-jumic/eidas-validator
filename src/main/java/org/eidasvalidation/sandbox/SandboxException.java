package org.eidasvalidation.sandbox;

import org.eidasvalidation.engine.ValidationException;

/** The worker process could not complete a job: it hung, died, or failed to start. */
public class SandboxException extends ValidationException {

    public enum Kind {
        /** The job exceeded its wall-clock limit; the worker was killed. */
        TIMEOUT,
        /** The worker process exited while working, e.g. out of memory or a parser crash. */
        CRASHED,
        /** The worker could not be started or did not finish loading trust data in time. */
        START_FAILED,
        /** The worker answered, but with an unexpected message. */
        PROTOCOL
    }

    private final Kind kind;

    public SandboxException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
