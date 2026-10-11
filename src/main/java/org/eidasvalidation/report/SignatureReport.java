package org.eidasvalidation.report;

import org.eidasvalidation.engine.SignatureResult;
import org.eidasvalidation.explain.Explanation;

/** One signature in the report: the plain-language reading next to the engine's result. */
public record SignatureReport(Explanation explanation, SignatureResult result) {
}
