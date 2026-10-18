package com.sclinic.patient;

import com.sclinic.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Patient demographic + administrative record.
 * Sensitive fields (medicalHistory, allergies) are personal health data.
 */
@Entity
@Table(name = "patient")
@Getter
@Setter
public class Patient {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    private String code;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private LocalDate dob;

    /** M / F / U */
    private String sex;

    private String phone;
    private String address;

    @Column(name = "medical_history")
    private String medicalHistory;

    private String allergies;
    private String note;

    // ---------- Identity numbers, encrypted at rest (V9) ----------
    //
    // These identify a person outright, so a leaked dump containing them is a
    // different order of harm from one containing appointment times. Mapped to
    // the *_enc columns added by V9; the original plaintext columns stay in the
    // database, unmapped, until V10 drops them in a later release.

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "national_id_enc")
    private String nationalId;     // CCCD/CMND

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "insurance_no_enc")
    private String insuranceNo;    // BHYT

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "tax_code_enc")
    private String taxCode;

    /**
     * Keyed hash of {@link #nationalId}, maintained by {@code PatientService}.
     * The encrypted column cannot be searched, so exact lookup by CCCD goes
     * through this. See {@code BlindIndex}.
     */
    @Column(name = "national_id_hash")
    private String nationalIdHash;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // ---------- Withdrawal and retention (V8) ----------

    /**
     * When the record was withdrawn from use. The row is never removed: a clinic
     * has a statutory duty to keep medical records, so "delete" can only mean
     * "stop using", and the history has to survive it.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    /** Required whenever {@code deletedAt} is set; the database enforces it too. */
    @Column(name = "delete_reason")
    private String deleteReason;

    /**
     * Earliest date this record may be considered for destruction. Null means
     * unknown, which is treated as "keep" — see {@code RetentionPolicy}.
     */
    @Column(name = "retention_until")
    private LocalDate retentionUntil;

    /**
     * Blocks destruction regardless of the retention period: an open complaint,
     * an inspection, a legal case.
     */
    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold = false;

    /** Starts the death-case retention clock, where a policy defines one. */
    @Column(name = "deceased_on")
    private LocalDate deceasedOn;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
