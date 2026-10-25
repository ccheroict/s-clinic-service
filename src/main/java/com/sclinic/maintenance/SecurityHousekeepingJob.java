package com.sclinic.maintenance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the housekeeping pass once a night.
 *
 * <p>Separate from the service so the work can be invoked directly by a test or
 * by an operator without waiting for a schedule, and so the schedule is the only
 * thing this class knows about.
 *
 * <p>Daily at 03:15 clinic time: outside opening hours, and not on the hour,
 * where every other scheduled thing in the world also runs.
 *
 * <p>Single-instance assumption. If the clinic ever runs two application
 * instances, both will run this, and both will race on the same rows — harmless
 * for deletes, which are idempotent, but it would double-count in the audit
 * entry. A lock belongs here before a second instance exists.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SecurityHousekeepingJob {

    private final SecurityHousekeepingService service;

    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Ho_Chi_Minh")
    public void run() {
        try {
            service.run();
        } catch (RuntimeException e) {
            // Logged rather than rethrown: a failed housekeeping run must not
            // stop the scheduler from trying again tomorrow, and nothing a user
            // is waiting for depends on it.
            log.error("Security housekeeping run failed", e);
        }
    }
}
