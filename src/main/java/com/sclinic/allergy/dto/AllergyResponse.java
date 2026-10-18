package com.sclinic.allergy.dto;

import com.sclinic.allergy.AllergyCertainty;
import com.sclinic.allergy.AllergySeverity;
import com.sclinic.allergy.PatientAllergy;
import com.sclinic.catalogue.CatalogAllergen;

import java.time.LocalDate;
import java.util.UUID;

/**
 * An allergy as the UI shows it.
 *
 * <p>Carries the allergen's code and name together: the code is what the
 * prescribing check uses and the name is what a clinician reads, and sending
 * only one of them means the client has to look the other up to display a row.
 *
 * @param severe convenience for the frontend warning. Derived rather than stored,
 *               so it cannot drift from {@code severity}
 */
public record AllergyResponse(
        UUID id,
        UUID patientId,
        String allergenCode,
        String allergenName,
        String allergenCategory,
        String reaction,
        AllergySeverity severity,
        boolean severe,
        AllergyCertainty certainty,
        LocalDate recordedOn,
        boolean active,
        String inactivationReason,
        String note
) {

    public static AllergyResponse from(PatientAllergy allergy, CatalogAllergen allergen) {
        return new AllergyResponse(
                allergy.getId(),
                allergy.getPatientId(),
                allergen.getCode(),
                allergen.getName(),
                allergen.getCategory(),
                allergy.getReaction(),
                allergy.getSeverity(),
                allergy.isSevere(),
                allergy.getCertainty(),
                allergy.getRecordedOn(),
                allergy.isActive(),
                allergy.getInactivationReason(),
                allergy.getNote());
    }
}
