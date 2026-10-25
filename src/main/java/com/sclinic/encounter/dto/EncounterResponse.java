package com.sclinic.encounter.dto;

import com.sclinic.encounter.Encounter;
import com.sclinic.encounter.EncounterDiagnosis;
import com.sclinic.encounter.EncounterStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An encounter as read back.
 *
 * @param clinicalSchemaVersion which version of the clinical form this record
 *        was written against. Sent because the client renders the form from the
 *        schema, and rendering a five-year-old record against today's form would
 *        drop the fields that have since been removed and demand ones that did
 *        not exist
 * @param editable whether the content can still change. Derived from the status
 *        rather than left to the client to work out, so that one rule decides it
 */
public record EncounterResponse(
        UUID id,
        UUID patientId,
        UUID doctorId,
        UUID appointmentId,
        Instant encounteredAt,
        String reason,
        EncounterStatus status,
        boolean editable,

        Map<String, Object> clinicalData,
        UUID clinicalSchemaId,
        String clinicalSpecialtyCode,
        Integer clinicalSchemaVersion,

        List<Diagnosis> diagnoses,

        String treatmentPlan,
        String advice,
        LocalDate followUpDate,
        String referralNote,

        /** The signed record this one corrects, when it is an amendment. */
        UUID amendsEncounterId,
        String amendmentReason,

        Instant createdAt,
        Instant updatedAt
) {

    public record Diagnosis(
            UUID id,
            String icd10Code,
            String icd10Name,
            boolean primary,
            String note
    ) {

        static Diagnosis from(EncounterDiagnosis diagnosis) {
            return new Diagnosis(
                    diagnosis.getId(),
                    diagnosis.getIcd10Code(),
                    diagnosis.getIcd10Name(),
                    diagnosis.isPrimary(),
                    diagnosis.getNote());
        }
    }

    public static EncounterResponse from(Encounter encounter,
                                         List<EncounterDiagnosis> diagnoses,
                                         String specialtyCode,
                                         Integer schemaVersion) {
        return new EncounterResponse(
                encounter.getId(),
                encounter.getPatientId(),
                encounter.getDoctorId(),
                encounter.getAppointmentId(),
                encounter.getEncounteredAt(),
                encounter.getReason(),
                encounter.getStatus(),
                encounter.isEditable(),
                encounter.getClinicalData(),
                encounter.getClinicalDataSchemaId(),
                specialtyCode,
                schemaVersion,
                diagnoses.stream().map(Diagnosis::from).toList(),
                encounter.getTreatmentPlan(),
                encounter.getAdvice(),
                encounter.getFollowUpDate(),
                encounter.getReferralNote(),
                encounter.getAmendsEncounterId(),
                encounter.getAmendmentReason(),
                encounter.getCreatedAt(),
                encounter.getUpdatedAt());
    }
}
