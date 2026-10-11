package org.eidasvalidation.explain;

/**
 * Plain-language reading of one signature result.
 *
 * @param verdict       the one-word answer
 * @param headline      one sentence a non-expert can act on
 * @param detail        what was checked and what was found, in plain words
 * @param qualification what the eIDAS qualification means for this signature, or what is missing to decide it
 * @param nextStep      what the reader can do about a non-valid result; null when nothing is needed
 */
public record Explanation(Verdict verdict, String headline, String detail, String qualification, String nextStep) {
}
