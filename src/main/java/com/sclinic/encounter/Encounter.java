package com.sclinic.encounter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * One examination, as recorded by the doctor who performed it.
 *
 * <p>The clinic-wide fields are columns; the dermatology-specific ones are a JSON
 * document validated against a versioned schema. That split is deliberate:
 * patient, doctor, time and diagnosis mean the same thing in every specialty and
 * are queried across all of them, while "body surface area affected" means
 * nothing outside dermatology and would be one more always-null column in every
 * other specialty's records.
 */
@Entity
@Table(name = "encounter")
@Getter
@Setter
public class Encounter {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "doctor_id", nullable = false)
    private UUID doctorId;

    /**
     * The appointment this grew out of, if there was one.
     *
     * <p>Null for a walk-in. Requiring an appointment would mean staff inventing
     * one retrospectively so the record could be written, which puts a fiction
     * in the schedule to satisfy a constraint.
     */
    @Column(name = "appointment_id")
    private UUID appointmentId;

    @Column(name = "encountered_at", nullable = false)
    private Instant encounteredAt;

    private String reason;

    @Column(name = "treatment_plan")
    private String treatmentPlan;

    private String advice;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "referral_note")
    private String referralNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EncounterStatus status = EncounterStatus.DRAFT;

    /**
     * Specialty-specific clinical data, validated against
     * {@link #clinicalDataSchemaId} on every write.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "clinical_data", columnDefinition = "jsonb")
    private Map<String, Object> clinicalData;

    /**
     * The schema version the data was validated against.
     *
     * <p>Recorded on the row rather than resolved at read time. Requirement 13.5
     * asks that a record written under an older schema stay readable once a newer
     * one exists, and the only way to honour that is to know which one applied —
     * validating a five-year-old record against today's form would reject it for
     * fields that did not exist when it was written.
     */
    @Column(name = "clinical_data_schema_id")
    private UUID clinicalDataSchemaId;

    /** The signed record this one corrects, for an amendment. */
    @Column(name = "amends_encounter_id")
    private UUID amendsEncounterId;

    @Column(name = "amendment_reason")
    private String amendmentReason;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Column(name = "delete_reason")
    private String deleteReason;

    @Column(name = "retention_until")
    private LocalDate retentionUntil;

    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Whether the content may still be changed. */
    public boolean isEditable() {
        return status == EncounterStatus.DRAFT && deletedAt == null;
    }
}
