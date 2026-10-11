package org.eidasvalidation.explain;

/** The one-word answer a reader sees first. */
public enum Verdict {
    /** Signature intact and chained to a trust anchor at the validation time. */
    VALID,
    /** Something is definitely wrong: changed document, broken signature, malformed structure. */
    INVALID,
    /** Could not decide: missing trust data, revocation data or proof of time. */
    INDETERMINATE,
    /** The document carries no signature at all. */
    NO_SIGNATURE
}
