package com.sclinic.encounter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request bodies for writing an encounter.
 *
 * <p>Together in one file because they are one form split across two calls:
 * create, then update until it is signed.
 */
public final class EncounterRequests {

    private EncounterRequests() {
    }

    /**
     * Opening a record.
     *
     * @param appointmentId   the appointment this grew out of, or null for a
     *                        walk-in. Supplying one moves that appointment to
     *                        IN_PROGRESS (requirement 13.6)
     * @param encounteredAt   when the examination happened. Defaults to now; a
     *                        future time is refused
     * @param specialtyCode   which clinical form to validate against. Defaults to
     *                        the clinic's configured specialty
     * @param clinicalData    specialty-specific fields, validated against the
     *                        current published schema for {@code specialtyCode}
     */
    public record Create(
            @NotNull(message = "patientId is required")
            UUID patientId,

            UUID appointmentId,

            Instant encounteredAt,

            @Size(max = 2000, message = "reason must be at most 2000 characters")
            String reason,

            String specialtyCode,

            Map<String, Object> clinicalData,

            @Valid
            List<DiagnosisEntry> diagnoses,

            @Size(max = 4000)
            String treatmentPlan,

            @Size(max = 4000)
            String advice,

            LocalDate followUpDate,

            @Size(max = 2000)
            String referralNote
    ) {
    }

    /**
     * Editing a draft.
     *
     * <p>Every field is replaced, including the diagnoses, rather than patched.
     * A partial update of a clinical record raises the question of how to remove
     * a diagnosis that was entered by mistake, and the usual answer — a null
     * meaning "leave alone" — makes removal impossible to express.
     */
    public record Update(
            Instant encounteredAt,

            @Size(max = 2000)
            String reason,

            Map<String, Object> clinicalData,

            @Valid
            List<DiagnosisEntry> diagnoses,

            @Size(max = 4000)
            String treatmentPlan,

            @Size(max = 4000)
            String advice,

            LocalDate followUpDate,

            @Size(max = 2000)
            String referralNote
    ) {
    }

    /**
     * One diagnosis.
     *
     * @param icd10Code the code from the catalogue. A code rather than an id
     *                  because that is what a clinician reads off a list and what
     *                  an integration would send; the service resolves it
     * @param primary   whether this is the main diagnosis. At most one entry may
     *                  set it, and exactly one must by the time the record is
     *                  signed
     */
    public record DiagnosisEntry(
            @NotBlank(message = "icd10Code is required")
            String icd10Code,

            boolean primary,

            @Size(max = 1000)
            String note
    ) {
    }
}
