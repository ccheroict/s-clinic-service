package com.sclinic.maintenance;

import com.sclinic.security.authevent.AuthEventRepository;
import com.sclinic.security.session.SessionToken;
import com.sclinic.security.session.SessionTokenRepository;
import com.sclinic.security.session.TokenScope;
import com.sclinic.staff.StaffRepository;
import com.sclinic.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
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
 * Housekeeping when the clinic has actually configured retention periods.
 *
 * <p>The companion class covers the default, where neither table is touched.
 * This one covers the opposite: once an operator has set a period, having
 * decided the trade, the rows past it do go — otherwise the setting would be a
 * switch that does nothing, which is worse than not offering it.
 *
 * <p>Thirty days here, which is far shorter than any clinic should choose. It is
 * a test fixture, not a recommendation; the point is that the boundary is
 * respected in both directions.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-HKC-001",
        "sclinic.auth.mfa-required-roles=",
        "sclinic.maintenance.session-token-retention=PT720H",
        "sclinic.maintenance.auth-event-retention=PT720H",
        "logging.level.org.hibernate.SQL=warn"
})
class SecurityHousekeepingConfiguredTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> EmbeddedPostgresSupport.jdbcUrlFor("housekeeping_cfg_it"));
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
    StaffRepository staffRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private UUID staffId;

    @BeforeEach
    void setUp() {
        sessionTokenRepository.deleteAll();
        jdbcTemplate.update("delete from auth_event");
        staffId = staffRepository.findByUsernameAndActiveTrue("admin").orElseThrow().getId();
    }

    private void session(long expiredDaysAgo) {
        SessionToken token = new SessionToken();
        token.setStaffId(staffId);
        token.setTokenHash(UUID.randomUUID().toString());
        token.setScope(TokenScope.FULL);
        token.setExpiresAt(Instant.now().minus(expiredDaysAgo, ChronoUnit.DAYS));
        sessionTokenRepository.save(token);
    }

    private void authEvent(long daysAgo) {
        jdbcTemplate.update("""
                insert into auth_event (username, staff_id, event_type, succeeded, created_at)
                values ('admin', ?::uuid, 'LOGIN_SUCCESS', true, now() - make_interval(days => ?))
                """, staffId.toString(), (int) daysAgo);
    }

    @Test
    void sessionsPastThePeriodAreRemovedAndRecentOnesAreNot() {
        session(60);
        session(5);

        SecurityHousekeepingService.Result result = service.run();

        assertThat(result.sessionTokens()).isEqualTo(1);
        assertThat(sessionTokenRepository.count()).isEqualTo(1);
    }

    @Test
    void authEventsPastThePeriodAreRemovedAndRecentOnesAreNot() {
        authEvent(60);
        authEvent(2);

        SecurityHousekeepingService.Result result = service.run();

        assertThat(result.authEvents()).isEqualTo(1);
        assertThat(authEventRepository.count()).isEqualTo(1);
    }

    @Test
    void aLiveSessionIsNeverRemovedHoweverShortThePeriod() {
        SessionToken live = new SessionToken();
        live.setStaffId(staffId);
        live.setTokenHash(UUID.randomUUID().toString());
        live.setScope(TokenScope.FULL);
        live.setExpiresAt(Instant.now().plus(8, ChronoUnit.HOURS));
        sessionTokenRepository.save(live);

        service.run();

        // The cut-off is on expiry, not on when the session started, so a long
        // retention period can never reach a session someone is still using.
        assertThat(sessionTokenRepository.count()).isEqualTo(1);
    }

    @Test
    void theDeletionIsAudited() {
        session(60);

        service.run();

        var entry = jdbcTemplate.queryForMap("""
                select detail::text as detail from audit_log
                where action = 'PURGE' order by created_at desc limit 1
                """);

        assertThat(entry.get("detail").toString()).contains("sessionTokens");
    }
}
