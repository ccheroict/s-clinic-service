package com.sclinic.security.password;

import com.sclinic.security.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Enforces password strength and non-reuse.
 *
 * <p>Strength rule: a minimum length plus at least three of the four character
 * classes. Length is the dominant factor, so the length floor is set high (12)
 * rather than demanding every class, which pushes users toward predictable
 * substitutions.
 */
@Component
@RequiredArgsConstructor
public class PasswordPolicy {

    private static final int REQUIRED_CHARACTER_CLASSES = 3;

    private final AuthProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final StaffPasswordHistoryRepository historyRepository;

    /**
     * Checks strength only. Used where no staff row exists yet (seeding).
     *
     * @throws WeakPasswordException when the password is too weak
     */
    public void validateStrength(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new WeakPasswordException("Password must not be empty");
        }
        if (rawPassword.length() < properties.minPasswordLength()) {
            throw new WeakPasswordException(
                    "Password must be at least " + properties.minPasswordLength() + " characters");
        }
        if (countCharacterClasses(rawPassword) < REQUIRED_CHARACTER_CLASSES) {
            throw new WeakPasswordException(
                    "Password must combine at least " + REQUIRED_CHARACTER_CLASSES
                            + " of: lowercase, uppercase, digit, special character");
        }
    }

    /**
     * Checks strength and that the password was not used recently by this staff
     * member, including their current one.
     *
     * @throws WeakPasswordException when the password is too weak or reused
     */
    public void validateForStaff(UUID staffId, String currentPasswordHash, String rawPassword) {
        validateStrength(rawPassword);

        if (currentPasswordHash != null && passwordEncoder.matches(rawPassword, currentPasswordHash)) {
            throw new WeakPasswordException("New password must differ from the current one");
        }

        List<StaffPasswordHistory> recent = historyRepository.findByStaffIdOrderByCreatedAtDesc(
                staffId, Limit.of(properties.passwordHistorySize()));

        boolean reused = recent.stream()
                .anyMatch(entry -> passwordEncoder.matches(rawPassword, entry.getPasswordHash()));
        if (reused) {
            throw new WeakPasswordException(
                    "New password must differ from the last " + properties.passwordHistorySize()
                            + " passwords used");
        }
    }

    /** Records a hash so future changes can detect reuse. */
    public void remember(UUID staffId, String passwordHash) {
        StaffPasswordHistory entry = new StaffPasswordHistory();
        entry.setStaffId(staffId);
        entry.setPasswordHash(passwordHash);
        historyRepository.save(entry);
    }

    private int countCharacterClasses(String password) {
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean special = false;

        for (char c : password.toCharArray()) {
            if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                special = true;
            }
        }
        return (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (special ? 1 : 0);
    }
}
