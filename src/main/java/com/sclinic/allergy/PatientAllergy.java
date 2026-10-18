package com.sclinic.allergy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One substance a patient reacts to, recorded so the system can act on it.
 *
 * <p>A coded allergen rather than a sentence, because a sentence cannot stop a
 * prescription. See migration V13.
 */
@Entity
@Table(name = "patient_allergy")
@Getter
@Setter
public class PatientAllergy {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "allergen_id", nullable = false)
    private UUID allergenId;

    /**
     * What actually happened. Free text on purpose: a clinical observation
     * forced into a code list loses the detail that makes it useful to the next
     * clinician.
     */
    private String reaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AllergySeverity severity;

    /**
     * A patient's own report and a confirmed test result are both worth
     * recording and must not look identical.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AllergyCertainty certainty = AllergyCertainty.REPORTED;

    @Column(name = "recorded_on", nullable = false)
    private LocalDate recordedOn;

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    /**
     * An allergy that turned out not to be one is retired, not deleted: that it
     * was believed, and acted on, is part of the record.
     */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "inactivated_at")
    private Instant inactivatedAt;

    @Column(name = "inactivated_by")
    private UUID inactivatedBy;

    @Column(name = "inactivation_reason")
    private String inactivationReason;

    private String note;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Whether this is the kind of allergy a prescriber must be stopped on. */
    public boolean isSevere() {
        return severity == AllergySeverity.SEVERE;
    }
}
