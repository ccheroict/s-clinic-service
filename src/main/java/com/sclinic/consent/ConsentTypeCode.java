package com.sclinic.consent;

/**
 * The kinds of consent the clinic collects, as required by requirement 11.1.
 *
 * <p>An enum as well as a table. The table is what the data references and what
 * an administrator configures wording against; this is what calling code names
 * when it asks whether a patient has consented, so that
 * {@code requireConsent(patientId, ConsentTypeCode.PHOTO_MARKETING)} cannot be
 * misspelled into a check that silently passes. A string literal there would
 * compile and then never match a row.
 */
public enum ConsentTypeCode {

    /** Examination and treatment, under Luật KCB 15/2023. */
    TREATMENT,

    /**
     * Processing of health data, which NĐ 13/2023 classes as sensitive personal
     * data. The consent whose wording has to say so in terms.
     */
    SENSITIVE_DATA,

    /** Taking and storing clinical photographs for the patient's own care. */
    CLINICAL_PHOTO,

    /**
     * Using clinical photographs for teaching.
     *
     * <p>Separate from {@link #CLINICAL_PHOTO}: agreeing that a doctor may look
     * at a photograph of your skin is not agreeing that a room of strangers may.
     */
    PHOTO_TRAINING,

    /**
     * Using clinical photographs in advertising.
     *
     * <p>Always separate, and never a condition of being treated.
     */
    PHOTO_MARKETING,

    /** Appointment reminders by message or call. Requirement 11.7. */
    APPOINTMENT_REMINDER,

    /**
     * A specific procedure.
     *
     * <p>Collected per procedure. One consent covering every future procedure is
     * not informed consent to any of them.
     */
    PROCEDURE
}
