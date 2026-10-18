package com.sclinic.access;

import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Tells the administrators that someone opened a record they had no relationship
 * with.
 *
 * <p>Requirement 8.3 asks for the administrators to be notified. This writes the
 * notification to the application log, named and at WARN, which is what a log
 * pipeline alerts on. It is not an email, and it is not a message anyone sees
 * inside the application: the system has no channel for either yet, and inventing
 * one here would mean picking a mail provider as a side effect of an access
 * control change.
 *
 * <p>Kept as its own type rather than a log line inside
 * {@link BreakGlassService} so that adding a real channel later is a new
 * implementation of this, not a change to the access logic. The seam is the
 * point; the current implementation is the part that is temporary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BreakGlassNotifier {

    private final StaffRepository staffRepository;

    @Transactional(readOnly = true)
    public void notifyAdministrators(BreakGlassGrant grant, String username) {
        List<String> administrators = staffRepository.findAll().stream()
                .filter(Staff::isActive)
                .filter(staff -> "ADMIN".equals(staff.getRole()))
                .map(Staff::getUsername)
                .toList();

        if (administrators.isEmpty()) {
            // Worth its own line: a clinic with no active administrator has
            // nobody to review break-glass use, which makes the accountability
            // this relies on nominal.
            log.error("BREAK-GLASS by {} on patient {} could not be notified: "
                            + "there is no active ADMIN account to notify.",
                    username, grant.getPatientId());
            return;
        }

        log.warn("BREAK-GLASS notification for {}: {} opened patient {} until {}. Reason recorded "
                        + "on grant {}.",
                administrators, username, grant.getPatientId(), grant.getExpiresAt(),
                grant.getId());
    }
}
