package com.sclinic.consent;

/**
 * How a consent was obtained.
 *
 * <p>Recorded because the methods are not equally good evidence, and a record
 * that does not say which was used cannot be weighed. Kept as a closed set so
 * that the weakest method is visible as a named category rather than disappearing
 * into free text.
 */
public enum ConsentCollectionMethod {

    /**
     * Signed on paper and filed. Still the common case in a Vietnamese clinic,
     * and a scan of a signed form is better evidence than a checkbox.
     */
    PAPER,

    /** Captured on a screen at the clinic with a staff member present. */
    IN_PERSON_DIGITAL,

    /** Agreed remotely, for instance before a first visit. */
    REMOTE_DIGITAL,

    /**
     * Given in speech and recorded by a staff member.
     *
     * <p>The weakest evidence here, named so that it is visible as such. A
     * verbal consent to marketing use of a patient's photographs is the kind of
     * record that does not survive being questioned.
     */
    VERBAL
}
