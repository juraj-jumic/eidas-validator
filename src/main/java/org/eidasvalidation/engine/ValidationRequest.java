package org.eidasvalidation.engine;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * @param document       the file to validate
 * @param validationTime the point in time the verdict refers to; usually now, but a fixed value
 *                       makes corpus results reproducible
 */
public record ValidationRequest(Path document, Instant validationTime) {

    public ValidationRequest {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(validationTime, "validationTime");
    }

    public static ValidationRequest now(Path document) {
        return new ValidationRequest(document, Instant.now());
    }
}
