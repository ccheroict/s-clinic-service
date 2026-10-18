package com.sclinic.retention;

import jakarta.persistence.Column;
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
import java.util.UUID;

/**
 * How long one kind of record must be kept.
 *
 * <p>Configuration rather than code, and deliberately not seeded with any value.
 * The duration comes from Luật KCB 15/2023; it is a legal question, and a number
 * guessed here would look authoritative while silently governing when medical
 * records become destructible. The clinic enters it once, with the article it
 * comes from.
 *
 * <p>With no row for a record type, retention dates are not computed at all, so
 * nothing is ever judged past its retention period. An unconfigured system keeps
 * everything, which is the only safe direction to fail in.
 */
@Entity
@Table(name = "retention_policy")
@Getter
@Setter
public class RetentionPolicy {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    /** Matches the {@code entityType} vocabulary of the audit trail. */
    @Column(name = "record_type", nullable = false, unique = true)
    private String recordType;

    @Column(name = "retention_years", nullable = false)
    private int retentionYears;

    /**
     * Separate period once the patient has died. Null means there is no separate
     * rule and the ordinary period applies.
     */
    @Column(name = "retention_years_after_death")
    private Integer retentionYearsAfterDeath;

    /**
     * The document and article this period comes from. A retention period nobody
     * can trace to a source is not auditable, and this is what an inspector asks
     * about first.
     */
    @Column(name = "legal_basis", nullable = false)
    private String legalBasis;

    private String note;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
