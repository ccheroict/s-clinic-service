package com.sclinic.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclinic.security.dto.ChangePasswordRequest;
import com.sclinic.security.dto.LoginRequest;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import com.sclinic.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit trail against a real database.
 *
 * <p>Covers the three things a regulator would actually ask to see: the trail
 * cannot be rewritten, it records enough context to trace an action back to a
 * session and an address, and tampering that got past the database is detectable
 * afterwards.
 *
 * <p>Not {@code @Transactional}: audit entries are written in their own
 * committed transaction, and the append-only triggers only fire on real
 * statements. A test transaction that rolled everything back at the end would
 * prove nothing here.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-username=admin",
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-AUDIT-001",
        "sclinic.auth.mfa-required-roles=",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
class AuditTrailIntegrationTest {

    private static final String ADMIN_PASSWORD = "AdminKham2026!Test";
    private static final String ROTATED_PASSWORD = "MatKhauMoi2027!";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrlFor("audit_it"));
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AuditChainVerifier verifier;

    @Autowired
    AuditService auditService;

    private String token;

    /**
     * Resets the admin credentials and gives every test a fresh full session.
     * The audit trail itself is deliberately never cleaned: it cannot be, which
     * is the point, so tests assert on relative counts rather than absolutes.
     */
    @BeforeEach
    void logIn() throws Exception {
        Staff admin = staffRepository.findByUsernameAndActiveTrue("admin").orElseThrow();
        admin.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
        admin.setMustChangePassword(false);
        admin.setFailedAttempts(0);
        admin.setLockedUntil(null);
        staffRepository.save(admin);

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "JUnit-Audit")
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("admin", ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertThat(response.get("scope").asText()).isEqualTo("FULL");
        token = response.get("token").asText();
    }

    // ---------- helpers ----------

    private long auditCount() {
        return jdbcTemplate.queryForObject("select count(*) from audit_log", Long.class);
    }

    private Map<String, Object> lastEntry() {
        return jdbcTemplate.queryForMap(
                "select * from audit_log order by id desc limit 1");
    }

    private long secondNewestEntryId() {
        return jdbcTemplate.queryForObject(
                "select id from audit_log order by id desc offset 1 limit 1", Long.class);
    }

    private void updateFacilityName(String name) throws Exception {
        mockMvc.perform(put("/api/facility")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("User-Agent", "JUnit-Audit")
                        .header("X-Forwarded-For", "203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "kcbCode", "KCB-AUDIT-001"))))
                .andExpect(status().isOk());
    }

    /**
     * Plays the part of someone with direct database access: turns off the
     * append-only triggers, lets the test tamper, then puts the trail back
     * exactly as it was.
     *
     * <p>The restore is what makes these tests independent of each other. The
     * trail is shared for the whole class and cannot be truncated — that is the
     * property under test — so a test that broke the chain and left it broken
     * would fail every test that ran after it, for reasons that have nothing to
     * do with what those tests are checking.
     *
     * <p>Nothing in the application can disable a trigger; it takes ownership of
     * the table, which is exactly the level of access this is simulating.
     */
    private void tamperThenRestore(Runnable tampering) {
        jdbcTemplate.execute("create table audit_log_backup as select * from audit_log");
        jdbcTemplate.execute("create table audit_head_backup as select * from audit_chain_head");

        jdbcTemplate.execute("alter table audit_log disable trigger user");
        try {
            tampering.run();
        } finally {
            jdbcTemplate.update("delete from audit_log");
            jdbcTemplate.update("insert into audit_log select * from audit_log_backup");
            jdbcTemplate.update("""
                    update audit_chain_head head
                       set head_hash = backup.head_hash,
                           entry_count = backup.entry_count
                      from audit_head_backup backup
                     where head.id = backup.id
                    """);

            jdbcTemplate.execute("alter table audit_log enable trigger user");
            jdbcTemplate.execute("drop table audit_log_backup");
            jdbcTemplate.execute("drop table audit_head_backup");
        }
    }

    /**
     * Runs the tampering and reports what verification made of it, with the trail
     * restored before the assertion runs.
     */
    private AuditChainVerifier.ChainVerification verifyAfterTampering(Runnable tampering) {
        var result = new AtomicReference<AuditChainVerifier.ChainVerification>();
        tamperThenRestore(() -> {
            tampering.run();
            result.set(verifier.verify());
        });
        return result.get();
    }

    // ---------- append-only enforcement ----------

    @Nested
    class AppendOnly {

        @Test
        void anEntryCannotBeUpdated() throws Exception {
            updateFacilityName("Phong kham A");
            long id = ((Number) lastEntry().get("id")).longValue();

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "update audit_log set action = 'VIEW' where id = ?", id))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
        }

        @Test
        void anEntryCannotBeDeleted() throws Exception {
            updateFacilityName("Phong kham B");
            long id = ((Number) lastEntry().get("id")).longValue();

            assertThatThrownBy(() -> jdbcTemplate.update("delete from audit_log where id = ?", id))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
        }

        @Test
        void theTrailCannotBeWipedInOneStatement() {
            // A row-level trigger does not fire for TRUNCATE, so without its own
            // statement-level trigger the whole trail would go in one line of SQL.
            assertThatThrownBy(() -> jdbcTemplate.execute("truncate table audit_log"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
        }

        @Test
        void aRejectedChangeLeavesTheTrailUntouched() throws Exception {
            updateFacilityName("Phong kham C");
            long before = auditCount();

            assertThatThrownBy(() -> jdbcTemplate.update("delete from audit_log"))
                    .isInstanceOf(DataAccessException.class);

            assertThat(auditCount()).isEqualTo(before);
        }

        @Test
        void appendingIsStillAllowed() throws Exception {
            long before = auditCount();

            updateFacilityName("Phong kham D");

            assertThat(auditCount()).isEqualTo(before + 1);
        }
    }

    // ---------- request context ----------

    @Nested
    class Context {

        @Test
        void recordsWhoDidItFromWhereAndInWhichSession() throws Exception {
            updateFacilityName("Phong kham E");

            Map<String, Object> entry = lastEntry();

            assertThat(entry.get("action")).isEqualTo("UPDATE");
            assertThat(entry.get("entity_type")).isEqualTo("facility");
            assertThat(entry.get("staff_id")).isNotNull();
            assertThat(entry.get("session_id")).isNotNull();
            assertThat(entry.get("user_agent")).isEqualTo("JUnit-Audit");
            // Honours the reverse-proxy header, so the real client shows up
            // rather than the proxy.
            assertThat(entry.get("ip")).isEqualTo("203.0.113.7");
        }

        @Test
        void tiesTheEntryToTheSessionThatMadeIt() throws Exception {
            updateFacilityName("Phong kham F");
            Object firstSession = lastEntry().get("session_id");

            // A second login is a different session, and the trail has to tell
            // them apart: that is what makes one stolen token traceable.
            logIn();
            updateFacilityName("Phong kham G");

            assertThat(lastEntry().get("session_id")).isNotEqualTo(firstSession);
        }

        @Test
        void worksWithoutARequestBehindIt() {
            // Startup seeding and scheduled jobs have no HTTP request; an audit
            // entry must still be written rather than blowing up.
            //
            // MockMvc leaves the request attributes of the previous call bound to
            // this thread, which a real container would not, so they are cleared
            // explicitly to actually reach the no-request path.
            RequestContextHolder.resetRequestAttributes();
            long before = auditCount();

            auditService.record(AuditAction.VIEW, "patient", UUID.randomUUID());

            assertThat(auditCount()).isEqualTo(before + 1);

            Map<String, Object> entry = lastEntry();
            assertThat(entry.get("ip")).isNull();
            assertThat(entry.get("user_agent")).isNull();
            // Still chained, so a job's action cannot be slipped in unnoticed.
            assertThat(entry.get("entry_hash")).isNotNull();
        }
    }

    // ---------- detail carries no values ----------

    @Nested
    class DetailContents {

        @Test
        void recordsFieldNamesWithoutTheValues() throws Exception {
            updateFacilityName("Ten Rat Dac Biet 12345");

            String detail = String.valueOf(lastEntry().get("detail"));

            assertThat(detail).contains("name").contains("changed");
            // The audit table must not become a second copy of business data.
            assertThat(detail).doesNotContain("Ten Rat Dac Biet 12345");
        }

        @Test
        void neverStoresAPassword() throws Exception {
            mockMvc.perform(post("/api/auth/change-password")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new ChangePasswordRequest(ADMIN_PASSWORD, ROTATED_PASSWORD))))
                    .andExpect(status().isOk());

            List<Map<String, Object>> everything = jdbcTemplate.queryForList(
                    "select coalesce(detail::text, '') as detail from audit_log");

            assertThat(everything)
                    .noneMatch(row -> String.valueOf(row.get("detail")).contains(ROTATED_PASSWORD));
        }
    }

    // ---------- chain verification ----------

    @Nested
    class Chain {

        @Test
        void reportsAnUntouchedTrailAsIntact() throws Exception {
            updateFacilityName("Phong kham H");

            AuditChainVerifier.ChainVerification result = verifier.verify();

            assertThat(result.intact()).isTrue();
            assertThat(result.problem()).isNull();
            assertThat(result.checkedEntries()).isGreaterThan(0);
        }

        @Test
        void everyEntryIsChainedToTheOneBeforeIt() throws Exception {
            updateFacilityName("Phong kham I");
            updateFacilityName("Phong kham J");

            List<Map<String, Object>> chain = jdbcTemplate.queryForList(
                    "select prev_hash, entry_hash from audit_log order by id asc");

            String expectedPrev = null;
            for (Map<String, Object> row : chain) {
                assertThat(row.get("prev_hash")).isEqualTo(expectedPrev);
                expectedPrev = String.valueOf(row.get("entry_hash"));
            }
        }

        @Test
        void theHeadRecordFollowsTheTrail() throws Exception {
            updateFacilityName("Phong kham K");

            Map<String, Object> head = jdbcTemplate.queryForMap(
                    "select head_hash, entry_count from audit_chain_head where id = 1");
            long chainedEntries = jdbcTemplate.queryForObject(
                    "select count(*) from audit_log where entry_hash is not null", Long.class);

            assertThat(head.get("head_hash")).isEqualTo(lastEntry().get("entry_hash"));
            assertThat(((Number) head.get("entry_count")).longValue()).isEqualTo(chainedEntries);
        }

        @Test
        void detectsAnAlteredEntryEvenWhenTheDatabaseAllowedIt() throws Exception {
            updateFacilityName("Phong kham L");
            updateFacilityName("Phong kham M");
            long middleId = secondNewestEntryId();

            AuditChainVerifier.ChainVerification result = verifyAfterTampering(() ->
                    jdbcTemplate.update("update audit_log set ip = '127.0.0.1' where id = ?", middleId));

            assertThat(result.intact()).isFalse();
            assertThat(result.firstBrokenEntryId()).isEqualTo(middleId);
            assertThat(result.problem()).contains("altered");
        }

        @Test
        void detectsARemovedEntryEvenWhenTheDatabaseAllowedIt() throws Exception {
            updateFacilityName("Phong kham N");
            updateFacilityName("Phong kham O");
            long middleId = secondNewestEntryId();

            AuditChainVerifier.ChainVerification result = verifyAfterTampering(() ->
                    jdbcTemplate.update("delete from audit_log where id = ?", middleId));

            assertThat(result.intact()).isFalse();
            assertThat(result.problem()).contains("removed or inserted");
        }

        @Test
        void detectsEntriesRemovedFromTheEnd() throws Exception {
            updateFacilityName("Phong kham P");
            long lastId = ((Number) lastEntry().get("id")).longValue();

            // Nothing follows the last entry, so no link breaks. Only the head
            // record notices.
            AuditChainVerifier.ChainVerification result = verifyAfterTampering(() ->
                    jdbcTemplate.update("delete from audit_log where id = ?", lastId));

            assertThat(result.intact()).isFalse();
            assertThat(result.problem()).contains("removed from the end");
        }

        @Test
        void restoresTheTrailSoLaterChecksAreNotMisled() throws Exception {
            updateFacilityName("Phong kham R");
            long lastId = ((Number) lastEntry().get("id")).longValue();

            verifyAfterTampering(() -> jdbcTemplate.update(
                    "update audit_log set ip = '127.0.0.1' where id = ?", lastId));

            // Guards the test harness itself: if the restore were incomplete,
            // every test after a tampering one would fail for the wrong reason.
            assertThat(verifier.verify().intact()).isTrue();
        }
    }

    // ---------- the verification endpoint ----------

    @Nested
    class VerifyEndpoint {

        @Test
        void reportsTheResultToAnAdmin() throws Exception {
            updateFacilityName("Phong kham Q");

            mockMvc.perform(post("/api/audit/verify-chain")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.intact").value(true))
                    .andExpect(jsonPath("$.headHash").isNotEmpty());
        }

        @Test
        void recordsThatTheCheckWasRun() throws Exception {
            mockMvc.perform(post("/api/audit/verify-chain")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());

            Map<String, Object> entry = lastEntry();
            assertThat(entry.get("action")).isEqualTo("VERIFY_CHAIN");
            assertThat(String.valueOf(entry.get("detail"))).contains("INTACT");
        }

        @Test
        void leavesTheTrailVerifiableAfterRecordingItsOwnRun() throws Exception {
            mockMvc.perform(post("/api/audit/verify-chain")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());

            // The entry describing the check is appended after the walk, so the
            // next check must still find an intact chain.
            mockMvc.perform(post("/api/audit/verify-chain")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.intact").value(true));
        }

        @Test
        void isRefusedWithoutASession() throws Exception {
            mockMvc.perform(post("/api/audit/verify-chain"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void isRefusedToNonAdminStaff() throws Exception {
            Staff receptionist = new Staff();
            receptionist.setUsername("letan-audit");
            receptionist.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
            receptionist.setFullName("Le Tan");
            receptionist.setRole("RECEPTIONIST");
            receptionist.setActive(true);
            staffRepository.save(receptionist);

            String body = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest("letan-audit", ADMIN_PASSWORD))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String receptionistToken = objectMapper.readTree(body).get("token").asText();

            mockMvc.perform(post("/api/audit/verify-chain")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistToken))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------- viewing a record is itself recorded ----------

    @Test
    void readingAPatientRecordLeavesATrace() throws Exception {
        String created = mockMvc.perform(post("/api/patients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "BN-AUDIT-1",
                                "fullName", "Nguyen Van Test"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String patientId = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(get("/api/patients/" + patientId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        Map<String, Object> entry = lastEntry();
        assertThat(entry.get("action")).isEqualTo("VIEW");
        assertThat(entry.get("entity_type")).isEqualTo("patient");
        assertThat(String.valueOf(entry.get("entity_id"))).isEqualTo(patientId);
    }
}
