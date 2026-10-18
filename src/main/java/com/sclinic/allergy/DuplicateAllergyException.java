package com.sclinic.allergy;

/**
 * A live allergy record already names this substance for this patient.
 *
 * <p>Refused rather than merged, because a duplicate is how a severity gets
 * silently downgraded: two rows for penicillin, one SEVERE and one MILD, and
 * whichever the prescribing check reads first decides what happens. The existing
 * record is edited or retired instead.
 */
public class DuplicateAllergyException extends RuntimeException {

    public DuplicateAllergyException(String allergenName) {
        super("An allergy to " + allergenName + " is already recorded for this patient. "
                + "Edit or retire the existing record rather than adding a second one.");
    }
}
