package com.sclinic.maintenance;

import com.sclinic.security.authevent.AuthEventRepository;
import com.sclinic.security.password.StaffPasswordHistory;
import com.sclinic.security.password.StaffPasswordHistoryRepository;
import com.sclinic.security.session.SessionToken;
import com.sclinic.security.session.SessionTokenRepository;
import com.sclinic.security.session.TokenScope;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import com.sclinic.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nightly housekeeping for the authentication tables.
 *
 * <p>Three tables gained a row per login or password change and nothing ever
 * removed any of them; {@code deleteExpiredBefore} had existed unused since the
 * session work. The interesting part of the fix is what it refuses to delete.
 *
 * <p>This class configures no retention periods, which is the default state, so
 * it is also the test that the default keeps the two tables a later
 * investigation would want.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-HK-001",
        "sclinic.auth.mfa-required-roles=",
        "sclinic.auth.password-history-size=3",
        "logging.level.org.hibernate.SQL=warn"
})
class SecurityHousekeepingIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> EmbeddedPostgresSupport.jdbcUrlFor("housekeeping_it"));
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
    }

    @Autowired
    SecurityHousekeepingService service;

    @Autowired
    SessionTokenRepository sessionTokenRepository;

    @Autowired
    AuthEventRepository authEventRepository;

    @Autowired
    StaffPasswordHistoryRepository passwordHistoryRepository;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private UUID staffId;

    @BeforeEach
    void setUp() {
        sessionTokenRepository.deleteAll();
        passwordHistoryRepository.deleteAll();
        jdbcTemplate.update("delete from auth_event");

        staffId = staffRepository.findByUsernameAndActiveTrue("admin").orElseThrow().getId();
    }

    // ---------- fixtures ----------

    private void expiredSession(long daysAgo) {
        SessionToken token = new SessionToken();
        token.setStaffId(staffId);
        token.setTokenHash(UUID.randomUUID().toString());
        token.setScope(TokenScope.FULL);
        token.setExpiresAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
        sessionTokenRepository.save(token);
    }

    private void liveSession() {
        SessionToken token = new SessionToken();
        token.setStaffId(staffId);
        token.setTokenHash(UUID.randomUUID().toString());
        token.setScope(TokenScope.FULL);
        token.setExpiresAt(Instant.now().plus(8, ChronoUnit.HOURS));
        sessionTokenRepository.save(token);
    }

    private void passwordHistoryRows(int count) {
        for (int i = 0; i < count; i++) {
            StaffPasswordHistory row = new StaffPasswordHistory();
            row.setStaffId(staffId);
            row.setPasswordHash("$2a$10$fakehashfortesting" + i);
            passwordHistoryRepository.save(row);
        }
    }

    private void authEvent(long daysAgo) {
        jdbcTemplate.update("""
                insert into auth_event (username, staff_id, event_type, succeeded, created_at)
                values ('admin', ?::uuid, 'LOGIN_SUCCESS', true, now() - make_interval(days => ?))
                """, staffId.toString(), (int) daysAgo);
    }

    // ---------- what the default refuses to delete ----------

    @Nested
    class WithNoRetentionConfigured {

        @Test
        void expiredSessionsAreKept() {
            expiredSession(400);
            expiredSession(5);

            service.run();

            // audit_log.session_id points at these rows. Deleting one leaves the
            // audit entries written during that session unable to say which
            // device or address they came from — the entry survives and its
            // context does not, which is a weaker record than the one the clinic
            // is obliged to keep.
            assertThat(sessionTokenRepository.count()).isEqualTo(2);
        }

        @Test
        void authEventsAreKept() {
            authEvent(400);
            authEvent(1);

            service.run();

            // This table is the record of who tried to log in and when. A job
            // that trims it by default is a job that erases evidence on a
            // schedule nobody chose.
            assertThat(authEventRepository.count()).isEqualTo(2);
        }

        @Test
        void theRunReportsThatItRemovedNothing() {
            expiredSession(400);
            authEvent(400);

            SecurityHousekeepingService.Result result = service.run();

            assertThat(result.sessionTokens()).isZero();
            assertThat(result.authEvents()).isZero();
        }

        @Test
        void aRunThatRemovesNothingWritesNoAuditEntry() {
            long before = purgeEntries();

            service.run();

            // An audit entry a night saying nothing happened is noise in the one
            // table that has to stay readable.
            assertThat(purgeEntries()).isEqualTo(before);
        }
    }

    // ---------- what it prunes regardless ----------

    @Nested
    class PasswordHistory {

        @Test
        void hashesBeyondTheReuseWindowAreRemoved() {
            passwordHistoryRows(7);

            SecurityHousekeepingService.Result result = service.run();

            // The policy keeps three, so rows four onwards cannot affect whether
            // a new password is a reuse. Keeping them would be keeping bcrypt
            // hashes of old staff passwords for no purpose at all.
            assertThat(passwordHistoryRepository.count()).isEqualTo(3);
            assertThat(result.passwordHistoryRows()).isEqualTo(4);
        }

        @Test
        void theNewestRowsAreTheOnesKept() {
            passwordHistoryRows(5);
            var newest = passwordHistoryRepository
                    .findByStaffIdOrderByCreatedAtDesc(staffId).get(0).getId();

            service.run();

            // Pruning the wrong end would let the most recently retired password
            // be reused immediately, which is the one the policy most wants to
            // block.
            assertThat(passwordHistoryRepository.findById(newest)).isPresent();
        }

        @Test
        void anAccountInsideTheWindowIsLeftAlone() {
            passwordHistoryRows(2);

            service.run();

            assertThat(passwordHistoryRepository.count()).isEqualTo(2);
        }

        @Test
        void needsNoConfiguredPeriod() {
            // Pruned by count, not by age, which is why no duration had to be
            // invented for it: the policy already says how many matter.
            passwordHistoryRows(10);

            service.run();

            assertThat(passwordHistoryRepository.count()).isEqualTo(3);
        }
    }

    // ---------- the audit entry ----------

    @Nested
    class Auditing {

        @Test
        void aRunThatRemovesSomethingIsRecordedWithCounts() {
            passwordHistoryRows(6);
            long before = purgeEntries();

            service.run();

            assertThat(purgeEntries()).isEqualTo(before + 1);

            var entry = jdbcTemplate.queryForMap("""
                    select action, entity_type, detail::text as detail from audit_log
                    where action = 'PURGE' order by created_at desc limit 1
                    """);

            // A purge that leaves no trace cannot be told apart from tampering,
            // and this one removes rows that security reviews read.
            assertThat(entry.get("entity_type")).isEqualTo("security_housekeeping");
            assertThat(entry.get("detail").toString()).contains("passwordHistory");
        }

        @Test
        void thePurgeActionIsNotTheSameAsADelete() {
            passwordHistoryRows(6);

            service.run();

            // DELETE is somebody deciding to stop using one record. A review
            // looking for who discarded a patient's record must not have to sift
            // nightly maintenance out of the results.
            Long deletes = jdbcTemplate.queryForObject("""
                    select count(*) from audit_log
                    where action = 'DELETE' and entity_type = 'security_housekeeping'
                    """, Long.class);

            assertThat(deletes).isZero();
        }

        @Test
        void noAccountIsNamedInTheEntry() {
            passwordHistoryRows(6);

            service.run();

            var entry = jdbcTemplate.queryForMap("""
                    select detail::text as detail from audit_log
                    where action = 'PURGE' order by created_at desc limit 1
                    """);

            // Counts only. Naming the accounts whose rows were trimmed would copy
            // staff activity patterns into a second table that outlives the rows
            // it describes.
            assertThat(entry.get("detail").toString()).doesNotContain(staffId.toString());
        }

        @Test
        void theEntryJoinsTheHashChain() {
            passwordHistoryRows(6);

            service.run();

            var entry = jdbcTemplate.queryForMap("""
                    select entry_hash from audit_log
                    where action = 'PURGE' order by created_at desc limit 1
                    """);

            assertThat(entry.get("entry_hash")).isNotNull();
        }
    }

    // ---------- live data is never touched ----------

    @Test
    void liveSessionsSurviveEveryRun() {
        liveSession();
        expiredSession(1);

        service.run();

        // Nothing here should ever log a working user out. The only session rows
        // in scope are ones that already stopped working.
        assertThat(sessionTokenRepository.count()).isEqualTo(2);
    }

    private long purgeEntries() {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'PURGE'", Long.class);
    }
}
