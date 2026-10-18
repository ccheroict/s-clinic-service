package com.sclinic.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * What one patient agreed to, when, and on what evidence.
 *
 * <p>Enough to demonstrate consent rather than merely assert it: the patient, the
 * exact wording, the purpose as agreed, the time, how it was collected, who
 * collected it, and when it ended if it has.
 */
@Entity
@Table(name = "consent_record")
@Getter
@Setter
public class ConsentRecord {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    /**
     * Carried alongside the version although the version implies it.
     *
     * <p>The question asked before every gated action is whether this patient has
     * a live consent of this type, and answering it through a join is a join on
     * the hottest check in the system. A composite foreign key on
     * {@code (version_id, consent_type_id)} stops the copy drifting.
     */
    @Column(name = "consent_type_id", nullable = false)
    private UUID consentTypeId;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    /**
     * The purpose as agreed, copied at the time of granting.
     *
     * <p>Not read back through the version, so that a later correction to the
     * wording cannot retroactively change what this patient agreed to — which is
     * the reason the wording is versioned in the first place.
     */
    @Column(nullable = false)
    private String purpose;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "collection_method", nullable = false)
    private ConsentCollectionMethod collectionMethod;

    /** Where the proof is: a scan reference, a signature id, a witness note. */
    private String evidence;

    @Column(name = "collected_by", nullable = false)
    private UUID collectedBy;

    /**
     * Requirement 11.6.
     *
     * <p>No reason is required. The right to withdraw is unconditional, and
     * demanding a justification would make the system an obstacle to a right it
     * exists to serve.
     */
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdrawn_by")
    private UUID withdrawnBy;

    @Column(name = "withdrawal_note")
    private String withdrawalNote;

    /**
     * Replaced by a newer consent.
     *
     * <p>Not a withdrawal, and recorded separately so it is not mistaken for
     * one: the wording changed and the patient agreed again. This record stays as
     * proof of what applied until then.
     */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "superseded_by")
    private UUID supersededBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** Whether this consent is the one currently in force. */
    public boolean isLive() {
        return withdrawnAt == null && supersededAt == null;
    }
}
