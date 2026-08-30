package com.sclinic.staff;

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
 * Staff member (doctor / receptionist / admin). Also the authentication principal.
 */
@Entity
@Table(name = "staff")
@Getter
@Setter
public class Staff {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /** DOCTOR / RECEPTIONIST / ADMIN */
    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private boolean active = true;

    // ---------- Account protection state (V5) ----------

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /**
     * Forces a password change before any business endpoint can be reached.
     * Set for seeded accounts and after an admin resets a password.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    /** Consecutive failed logins; reset to zero on success. */
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    // ---------- Two-factor authentication (V6) ----------

    /** Base32 TOTP shared secret. Present once enrolment starts. */
    @Column(name = "totp_secret")
    private String totpSecret;

    /** True once the staff member proved they can generate a valid code. */
    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled = false;

    @Column(name = "totp_confirmed_at")
    private Instant totpConfirmedAt;

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }
}
