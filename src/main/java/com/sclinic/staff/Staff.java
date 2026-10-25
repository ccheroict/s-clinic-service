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

    // ---------- Columns V1 created and nothing mapped ----------
    //
    // These existed in the schema from the first migration and were missing from
    // this entity, so the application could neither read nor write them. Harmless
    // while nothing needed them; not harmless now that a signature has to carry
    // who signed and under what licence, and that the clinic's own staff list
    // should be able to show a phone number without a direct SQL query.

    /** Relevant for doctors; null for the rest. */
    @Column(name = "specialty_id")
    private UUID specialtyId;

    private String phone;

    private String email;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    // ---------- Credentials to practise and to sign (V17) ----------

    /**
     * Số giấy phép hành nghề.
     *
     * <p>Requirement 14.3. A signed record needs this to be defensible: "signed
     * by Dr X" means little without the licence that let Dr X sign it.
     */
    @Column(name = "practice_licence_no")
    private String practiceLicenceNo;

    /**
     * Mã liên thông of the doctor in the national system.
     *
     * <p>Separate from the licence number because they are issued by different
     * bodies and a doctor may hold one before the other.
     */
    @Column(name = "national_doctor_code")
    private String nationalDoctorCode;

    /**
     * Phạm vi hành nghề, as worded on the licence.
     *
     * <p>Text rather than a code list: the wording on a Vietnamese licence is not
     * drawn from a closed vocabulary, and flattening it into categories would
     * lose the part a reviewer would actually want to read.
     */
    @Column(name = "practice_scope")
    private String practiceScope;

    /**
     * Handle for this person's signing key at the remote provider.
     *
     * <p>Never the key itself. Remote signing exists so the private key stays
     * with the provider and the signer approves each use on their own device; if
     * this field ever held key material, the arrangement would have lost the
     * property that makes it worth having.
     */
    @Column(name = "signing_credential_id")
    private String signingCredentialId;

    /** Which provider that credential belongs to. */
    @Column(name = "signing_provider")
    private String signingProvider;

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

    /**
     * Time step of the last accepted TOTP code.
     *
     * <p>A code whose step is not strictly greater is refused as a replay. Null
     * until the first successful verification.
     *
     * <p>Never written through this field. {@code StaffRepository.advanceTotpStep}
     * updates it conditionally, because two requests presenting the same code at
     * the same moment would both read the same value here and both decide they
     * were first.
     */
    @Column(name = "totp_last_step")
    private Long totpLastStep;

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }
}
