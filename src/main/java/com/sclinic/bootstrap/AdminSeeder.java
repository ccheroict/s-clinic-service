package com.sclinic.bootstrap;

import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates an initial ADMIN account on first startup when no staff exists,
 * so the system is usable out of the box. Credentials come from
 * {@code sclinic.bootstrap.*} (default admin/admin) and MUST be changed in production.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements CommandLineRunner {

    private final StaffRepository staffRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${sclinic.bootstrap.admin-username:admin}")
    private String adminUsername;

    @Value("${sclinic.bootstrap.admin-password:admin}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        if (staffRepository.count() > 0) {
            return;
        }
        Staff admin = new Staff();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setFullName("System Administrator");
        admin.setRole("ADMIN");
        admin.setActive(true);
        staffRepository.save(admin);

        log.warn("Created initial ADMIN account '{}'. CHANGE THE DEFAULT PASSWORD IMMEDIATELY.",
                adminUsername);
    }
}
