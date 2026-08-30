package com.sclinic.security.mfa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * A single-use recovery code, for when the authenticator device is lost.
 * Only the bcrypt hash is kept; the plain code is shown once at enrolment.
 */
@Entity
@Table(name = "staff_backup_code")
@Getter
@Setter
public class StaffBackupCode {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "staff_id", nullable = false)
    private UUID staffId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    /** Set the moment the code is spent, so it can never be replayed. */
    @Column(name = "used_at")
    private Instant usedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
