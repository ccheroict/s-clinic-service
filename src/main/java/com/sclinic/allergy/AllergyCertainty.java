package com.sclinic.allergy;

/**
 * How well established the allergy is.
 *
 * <p>Recorded separately from severity because the two are independent and
 * conflating them loses both: a suspected anaphylaxis and a confirmed rash are
 * different situations, and a single "how serious is this" field cannot express
 * either.
 */
public enum AllergyCertainty {

    /** Inferred from the history; nobody has said so outright. */
    SUSPECTED,

    /** The patient or their family says so. The common case, and not a lesser one. */
    REPORTED,

    /** Established by testing or by a documented reaction under observation. */
    CONFIRMED
}
