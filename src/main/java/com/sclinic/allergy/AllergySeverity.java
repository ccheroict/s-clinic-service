package com.sclinic.allergy;

/**
 * How bad the reaction was.
 *
 * <p>Three levels rather than a finer scale. The decision this drives is binary
 * at the point of prescribing — warn, or refuse — and a scale with more steps
 * than the decisions it informs invites arguments about whether something is a 4
 * or a 5 instead of recording what happened.
 */
public enum AllergySeverity {

    /** Rash, mild itching. Worth knowing, not worth blocking a prescription over. */
    MILD,

    MODERATE,

    /**
     * Anaphylaxis, angioedema, anything that needed emergency treatment. The
     * level at which prescribing the substance has to be stopped rather than
     * merely flagged.
     */
    SEVERE
}
