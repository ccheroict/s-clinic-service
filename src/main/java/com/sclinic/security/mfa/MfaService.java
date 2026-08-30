package com.sclinic.security.mfa;

import com.sclinic.security.AuthProperties;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Enrolment and verification of TOTP second factors.
 *
 * <p>A secret is written at the start of enrolment but {@code totpEnabled} stays
 * false until the staff member proves they can produce a valid code. That avoids
 * locking someone out of their own account because they scanned the QR into an
 * app that never worked.
 */
@Service
@RequiredArgsConstructor
public class MfaService {

    private static final String BACKUP_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int BACKUP_CODE_LENGTH = 10;

    private final StaffRepository staffRepository;
    private final StaffBackupCodeRepository backupCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final Clock clock;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Starts (or restarts) enrolment: issues a fresh secret and the provisioning
     * URI. Restarting is allowed while unconfirmed, so a failed scan is recoverable.
     *
     * <p>Refused outright once a factor is confirmed. Without that check, anyone
     * holding a full session could call enroll, scan the new secret into their own
     * authenticator, confirm it, and walk away with the account's second factor and
     * a fresh set of recovery codes — which is exactly the outcome that keeping
     * {@code resetMfa} admin-only is meant to prevent. It also removes a way for a
     * legitimate user to destroy a working factor by opening the setup screen and
     * abandoning it, since the old secret and recovery codes stop working the
     * moment a new secret is issued.
     *
     * @throws MfaAlreadyEnrolledException if the account already has a confirmed factor
     */
    @Transactional
    public Enrolment beginEnrolment(Staff staff) {
        if (staff.isTotpEnabled()) {
            throw new MfaAlreadyEnrolledException();
        }

        String secret = TotpGenerator.generateSecret();
        staff.setTotpSecret(secret);
        staff.setTotpEnabled(false);
        staff.setTotpConfirmedAt(null);
        staffRepository.save(staff);

        String uri = TotpGenerator.provisioningUri(
                secret, staff.getUsername(), properties.totpIssuer());
        return new Enrolment(secret, uri);
    }

    /**
     * Completes enrolment once the staff member produces a valid code, and issues
     * their single-use recovery codes.
     *
     * @return the plain recovery codes, shown exactly once
     * @throws MfaFailedException if the code does not verify
     */
    @Transactional
    public List<String> confirmEnrolment(Staff staff, String code) {
        if (staff.getTotpSecret() == null) {
            throw new MfaFailedException("No enrolment in progress");
        }
        if (!TotpGenerator.verify(staff.getTotpSecret(), code, clock.instant(),
                properties.totpWindowSteps())) {
            throw new MfaFailedException("Invalid authentication code");
        }

        staff.setTotpEnabled(true);
        staff.setTotpConfirmedAt(clock.instant());
        staffRepository.save(staff);

        return regenerateBackupCodes(staff.getId());
    }

    /**
     * Verifies a login challenge. Accepts either a TOTP code or an unused backup
     * code; a backup code is burned on use.
     *
     * @throws MfaFailedException if neither matches
     */
    @Transactional
    public void verifyChallenge(Staff staff, String code) {
        if (!staff.isTotpEnabled() || staff.getTotpSecret() == null) {
            throw new MfaFailedException("Two-factor authentication is not set up");
        }

        if (TotpGenerator.verify(staff.getTotpSecret(), code, clock.instant(),
                properties.totpWindowSteps())) {
            return;
        }
        if (consumeBackupCode(staff.getId(), code)) {
            return;
        }
        throw new MfaFailedException("Invalid authentication code");
    }

    /**
     * Clears the second factor so the staff member must enrol again.
     * Admin-only recovery path for a lost device.
     */
    @Transactional
    public void reset(Staff staff) {
        staff.setTotpSecret(null);
        staff.setTotpEnabled(false);
        staff.setTotpConfirmedAt(null);
        staffRepository.save(staff);
        backupCodeRepository.deleteByStaffId(staff.getId());
    }

    public long remainingBackupCodes(UUID staffId) {
        return backupCodeRepository.countByStaffIdAndUsedAtIsNull(staffId);
    }

    /** Replaces any outstanding codes with a fresh set. */
    @Transactional
    public List<String> regenerateBackupCodes(UUID staffId) {
        backupCodeRepository.deleteByStaffId(staffId);

        List<String> plainCodes = new ArrayList<>(properties.backupCodeCount());
        for (int i = 0; i < properties.backupCodeCount(); i++) {
            String plain = generateBackupCode();
            plainCodes.add(plain);

            StaffBackupCode entity = new StaffBackupCode();
            entity.setStaffId(staffId);
            entity.setCodeHash(passwordEncoder.encode(plain));
            backupCodeRepository.save(entity);
        }
        return plainCodes;
    }

    private boolean consumeBackupCode(UUID staffId, String candidate) {
        String normalised = candidate == null ? "" : candidate.trim().toUpperCase();
        if (normalised.isEmpty()) {
            return false;
        }

        for (StaffBackupCode stored : backupCodeRepository.findByStaffIdAndUsedAtIsNull(staffId)) {
            if (passwordEncoder.matches(normalised, stored.getCodeHash())) {
                // Conditional update: whoever gets the row count wins. Two requests
                // presenting the same code at the same time would both pass the
                // match above, so marking it used here without a condition would
                // let a single-use code be used twice.
                return backupCodeRepository.spend(stored.getId(), clock.instant()) == 1;
            }
        }
        return false;
    }

    private String generateBackupCode() {
        StringBuilder code = new StringBuilder(BACKUP_CODE_LENGTH);
        for (int i = 0; i < BACKUP_CODE_LENGTH; i++) {
            code.append(BACKUP_CODE_ALPHABET.charAt(
                    secureRandom.nextInt(BACKUP_CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * @param secret         base32 shared secret, to display as text
     * @param provisioningUri otpauth URI, to render as a QR code
     */
    public record Enrolment(String secret, String provisioningUri) {
    }
}
