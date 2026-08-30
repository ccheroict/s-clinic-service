package com.sclinic.bootstrap;

import com.sclinic.security.password.PasswordPolicy;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first ADMIN account when no staff exists.
 *
 * <p>The password is randomly generated and printed to the log exactly once,
 * and the account is flagged to require a change at first login. Earlier this
 * seeder used a hard-coded {@code admin/admin}, which meant every deployment
 * shipped with publicly known credentials guarding health records.
 *
 * <p>A password may be supplied via {@code sclinic.bootstrap.admin-password}
 * for automated environments. It must still satisfy the password policy, and the
 * account is still required to change it at first login.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements CommandLineRunner {

    private final StaffRepository staffRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    @Value("${sclinic.bootstrap.admin-username:admin}")
    private String adminUsername;

    /** Empty means "generate a random one". */
    @Value("${sclinic.bootstrap.admin-password:}")
    private String configuredPassword;

    @Override
    public void run(String... args) {
        if (staffRepository.count() > 0) {
            return;
        }

        boolean generated = configuredPassword == null || configuredPassword.isBlank();
        String password = generated ? InitialPasswordGenerator.generate() : configuredPassword;

        // A configured password that fails the policy must stop the deployment
        // rather than silently create a weak administrator account.
        passwordPolicy.validateStrength(password);

        Staff admin = new Staff();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setFullName("System Administrator");
        admin.setRole("ADMIN");
        admin.setActive(true);
        admin.setMustChangePassword(true);
        staffRepository.save(admin);

        if (generated) {
            log.warn("""

                    ============================================================
                    Created initial ADMIN account.
                      username: {}
                      password: {}
                    This password is shown once and is not recoverable.
                    You must change it at first login.
                    ============================================================""",
                    adminUsername, password);
        } else {
            log.warn("Created initial ADMIN account '{}' from configuration. "
                    + "A password change is required at first login.", adminUsername);
        }
    }
}
