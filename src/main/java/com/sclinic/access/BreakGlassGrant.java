package com.sclinic.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Temporary access to one patient's record, granted by the clinician who needed
 * it and answerable afterwards.
 *
 * <p>See migration V11 for why this exists at all rather than the access rule
 * simply being absolute.
 */
@Entity
@Table(name = "break_glass_grant")
@Getter
@Setter
public class BreakGlassGrant {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    /** Why the clinician needed access. Mandatory; the database enforces it too. */
    @Column(nullable = false)
    private String reason;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set when an admin ends the grant early. The record of it stays. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean isActiveAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }
}
