package com.sclinic.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclinic.access.dto.BreakGlassRequest;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Least-privilege access to patient records, end to end.
 *
 * <p>Three staff members, one patient. The doctor who treats them gets in; the
 * doctor who does not is refused and told how to get in anyway; the receptionist
 * gets in but sees no clinical data.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-username=admin",
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-ACCESS-001",
        "sclinic.auth.mfa-required-roles=",
        "sclinic.access.break-glass-duration=PT2H",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
class BreakGlassIntegrationTest {

    private static final String PASSWORD = "AdminKham2026!Test";
    private static final String CLINICAL_NOTE = "Viem da co dia, dang dieu tri corticoid";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrlFor("access_it"));
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
    BreakGlassGrantRepository grantRepository;

    private String adminToken;
    private String treatingDoctorToken;
    private String otherDoctorToken;
    private String receptionistToken;

    private UUID treatingDoctorId;
    private UUID otherDoctorId;
    private String patientId;
    private String patientName;

    @BeforeEach
    void setUpStaffAndPatient() throws Exception {
        Staff admin = staffRepository.findByUsernameAndActiveTrue("admin").orElseThrow();
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        admin.setMustChangePassword(false);
        admin.setFailedAttempts(0);
        admin.setLockedUntil(null);
        staffRepository.save(admin);
        adminToken = logIn("admin");

        treatingDoctorId = ensureStaff("bs-dieu-tri", "DOCTOR");
        otherDoctorId = ensureStaff("bs-khac", "DOCTOR");
        ensureStaff("le-tan", "RECEPTIONIST");

        treatingDoctorToken = logIn("bs-dieu-tri");
        otherDoctorToken = logIn("bs-khac");
        receptionistToken = logIn("le-tan");

        patientName = uniqueName();
        patientId = createPatient(patientName);
        bookAppointment(patientId, treatingDoctorId);

        grantRepository.deleteAll();
    }

    // ---------- fixtures ----------

    private UUID ensureStaff(String username, String role) {
        return staffRepository.findByUsernameAndActiveTrue(username)
                .map(existing -> {
                    existing.setPasswordHash(passwordEncoder.encode(PASSWORD));
                    existing.setMustChangePassword(false);
                    existing.setFailedAttempts(0);
                    existing.setLockedUntil(null);
                    return staffRepository.save(existing).getId();
                })
                .orElseGet(() -> {
                    Staff staff = new Staff();
                    staff.setUsername(username);
                    staff.setPasswordHash(passwordEncoder.encode(PASSWORD));
                    staff.setFullName(username);
                    staff.setRole(role);
                    staff.setActive(true);
                    return staffRepository.save(staff).getId();
                });
    }

    private String logIn(String username) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(username, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    /**
     * Patients accumulate across the tests in this class, and the list is paged,
     * so each one gets a name unique enough to be searched for by itself. Reading
     * the first page and hoping the newest patient is on it breaks as soon as
     * there are more than a page of them.
     */
    private String uniqueName() {
        return "Benh Nhan " + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createPatient(String fullName) throws Exception {
        String body = mockMvc.perform(post("/api/patients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "BN-ACC-" + UUID.randomUUID().toString().substring(0, 8),
                                "fullName", fullName,
                                "phone", "0911222333",
                                "medicalHistory", CLINICAL_NOTE,
                                "allergies", "Penicillin"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    /** Lists what one staff member can see, narrowed to a single patient's name. */
    private JsonNode listByName(String token, String fullName) throws Exception {
        String body = mockMvc.perform(get("/api/patients")
                        .queryParam("q", fullName)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** Establishes the treating relationship the access rule derives from. */
    private void bookAppointment(String patient, UUID doctorId) {
        jdbcTemplate.update("""
                insert into appointment (id, patient_id, doctor_id, scheduled_at, duration_min, status)
                values (gen_random_uuid(), ?::uuid, ?::uuid, now(), 30, 'BOOKED')
                """, patient, doctorId);
    }

    private JsonNode getPatient(String token, int expectedStatus) throws Exception {
        String body = mockMvc.perform(get("/api/patients/" + patientId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode breakGlass(String token, String reason, int expectedStatus) throws Exception {
        String body = mockMvc.perform(post("/api/patients/" + patientId + "/break-glass")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BreakGlassRequest(reason))))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : objectMapper.readTree(body);
    }

    // ---------- requirement 8.1, 8.2 ----------

    @Nested
    class BulkReadsAreRecorded {

        private long listEntries(String entityType, String token, String query) throws Exception {
            long before = listAuditCount(entityType);

            mockMvc.perform(get(query)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());

            return listAuditCount(entityType) - before;
        }

        private long listAuditCount(String entityType) {
            return jdbcTemplate.queryForObject("""
                    select count(*) from audit_log
                    where entity_type = ? and action = 'VIEW' and entity_id is null
                      and detail ->> 'scope' = 'list'
                    """, Long.class, entityType);
        }

        @Test
        void aReceptionistListingEveryPatientIsRecorded() throws Exception {
            // A receptionist sees no clinical fields, but they do see the name,
            // phone and address of every patient in the clinic, which is personal
            // data under NĐ 13/2023 whether or not a diagnosis is attached.
            assertThat(listEntries("patient", receptionistToken, "/api/patients")).isEqualTo(1);
        }

        @Test
        void anAdminListingEveryPatientIsRecorded() throws Exception {
            assertThat(listEntries("patient", adminToken, "/api/patients")).isEqualTo(1);
        }

        @Test
        void aDoctorListingTheirOwnPatientsIsNotRecorded() throws Exception {
            // Already narrowed to the patients they treat, so it carries no more
            // than opening each of them would — and opening one of their own
            // patients is deliberately not recorded either, because an entry per
            // routine clinical read buries the entries that matter.
            assertThat(listEntries("patient", treatingDoctorToken, "/api/patients")).isZero();
        }

        @Test
        void theEntryIsNotTiedToOnePatientBecauseTheReadWasNot() throws Exception {
            listEntries("patient", adminToken, "/api/patients");

            Map<String, Object> entry = jdbcTemplate.queryForMap("""
                    select entity_id, detail::text as detail from audit_log
                    where entity_type = 'patient' and action = 'VIEW' and entity_id is null
                    order by created_at desc limit 1
                    """);

            // Naming one patient would be a claim about the read that is not
            // true; the page covered many.
            assertThat(entry.get("entity_id")).isNull();
            assertThat(entry.get("detail").toString()).contains("results");
        }

        @Test
        void theSearchKeywordIsNotWrittenToTheTrail() throws Exception {
            mockMvc.perform(get("/api/patients")
                            .queryParam("q", patientName)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk());

            Map<String, Object> entry = jdbcTemplate.queryForMap("""
                    select detail::text as detail from audit_log
                    where entity_type = 'patient' and action = 'VIEW' and entity_id is null
                    order by created_at desc limit 1
                    """);

            // People search by patient name, so the term is usually somebody's
            // name. Storing it would copy identifying data into a table with
            // different retention and weaker access rules than the record it came
            // from — the same reason a delete reason is not copied into detail.
            assertThat(entry.get("detail").toString()).doesNotContain(patientName);
            // That the read was narrowed is still recorded: paging the whole list
            // reads differently from looking one person up.
            assertThat(entry.get("detail").toString()).contains("filtered");
            assertThat(entry.get("detail").toString()).contains("true");
        }

        /*
         * The equivalent tests for GET /api/appointments are not here yet, and
         * deliberately so.
         *
         * AppointmentService.list does write the audit entry, and the code for it
         * is in this commit. The endpoint itself cannot be driven from a test:
         * AppointmentRepository.findWithFilters is built from
         * (? is null or column = ?) clauses, and PostgreSQL will not infer a type
         * for a bare parameter compared against null, so the query is a SQL
         * grammar error whenever any filter is left out — with or without a date.
         *
         * That is a pre-existing fault these tests uncovered rather than caused,
         * and it is larger than this batch: the endpoint is effectively unusable
         * unless a client supplies every filter. Opening it properly, with a test
         * that pins the unfiltered case, is its own item in task 1.9c rather than
         * something to bolt onto an audit change.
         */

        @Test
        void theListEntryJoinsTheHashChainLikeAnyOther() throws Exception {
            listEntries("patient", adminToken, "/api/patients");

            Map<String, Object> entry = jdbcTemplate.queryForMap("""
                    select entry_hash from audit_log
                    where entity_type = 'patient' and action = 'VIEW' and entity_id is null
                    order by created_at desc limit 1
                    """);

            // An audit entry outside the chain is an audit entry that can be
            // deleted without trace, which would make recording the read pointless.
            assertThat(entry.get("entry_hash")).isNotNull();
        }
    }

    @Nested
    class TreatingRelationship {

        @Test
        void theDoctorWhoTreatsThePatientSeesTheWholeRecord() throws Exception {
            JsonNode patient = getPatient(treatingDoctorToken, 200);

            assertThat(patient.get("medicalHistory").asText()).isEqualTo(CLINICAL_NOTE);
            assertThat(patient.get("allergies").asText()).isEqualTo("Penicillin");
        }

        @Test
        void anotherDoctorIsRefused() throws Exception {
            getPatient(otherDoctorToken, 403);
        }

        @Test
        void theRefusalSaysHowToGetThrough() throws Exception {
            JsonNode problem = getPatient(otherDoctorToken, 403);

            assertThat(problem.get("detail").asText()).contains("break-glass");
            assertThat(problem.get("breakGlassPath").asText())
                    .isEqualTo("/api/patients/" + patientId + "/break-glass");
        }

        @Test
        void theListShowsADoctorOnlyTheirOwnPatients() throws Exception {
            assertThat(listByName(treatingDoctorToken, patientName).get("totalElements").asLong())
                    .isEqualTo(1);

            assertThat(listByName(otherDoctorToken, patientName).get("totalElements").asLong())
                    .isZero();
        }

        /**
         * The list is filtered in the query, not after paging. Post-filtering a
         * page returns short pages and a wrong total, and a patient list that
         * silently drops entries is worse than one that refuses.
         */
        @Test
        void theTotalReflectsTheFilterNotThePageContents() throws Exception {
            JsonNode page = listByName(otherDoctorToken, patientName);

            assertThat(page.get("totalElements").asLong()).isZero();
            assertThat(page.get("content")).isEmpty();
        }
    }

    // ---------- requirement 8.6 ----------

    @Nested
    class Receptionists {

        @Test
        void reachEveryPatient() throws Exception {
            getPatient(receptionistToken, 200);
        }

        /**
         * The clinical fields are absent from the response, not blank. A nulled
         * field cannot be told apart from an empty one, and "no recorded
         * allergies" must never be confused with "you are not allowed to know".
         */
        @Test
        void seeNoClinicalData() throws Exception {
            JsonNode patient = getPatient(receptionistToken, 200);

            assertThat(patient.has("medicalHistory")).isFalse();
            assertThat(patient.has("allergies")).isFalse();
            assertThat(patient.has("note")).isFalse();
            assertThat(patient.toString()).doesNotContain(CLINICAL_NOTE);
        }

        @Test
        void stillSeeWhatTheyNeedToBookAndBill() throws Exception {
            JsonNode patient = getPatient(receptionistToken, 200);

            assertThat(patient.get("fullName").asText()).isEqualTo(patientName);
            assertThat(patient.get("phone").asText()).isEqualTo("0911222333");
            assertThat(patient.has("insuranceNo")).isTrue();
        }

        @Test
        void seeNoClinicalDataInTheListEither() throws Exception {
            String body = listByName(receptionistToken, patientName).toString();

            assertThat(body).doesNotContain(CLINICAL_NOTE).doesNotContain("Penicillin");
        }

        @Test
        void cannotRequestBreakGlass() throws Exception {
            // Nothing to escalate to: they already reach every administrative
            // record and have no clinical access to gain.
            breakGlass(receptionistToken, "khong can thiet nhung thu xem", 403);
        }
    }

    // ---------- requirement 8.3, 8.4, 8.5 ----------

    @Nested
    class BreakGlass {

        @Test
        void opensTheRecordForADoctorWhoStatesAReason() throws Exception {
            getPatient(otherDoctorToken, 403);

            breakGlass(otherDoctorToken, "Benh nhan cap cuu, bac si dieu tri khong co mat", 200);

            JsonNode patient = getPatient(otherDoctorToken, 200);
            assertThat(patient.get("medicalHistory").asText()).isEqualTo(CLINICAL_NOTE);
        }

        @Test
        void returnsWhenTheAccessExpiresAndSaysItWasRecorded() throws Exception {
            JsonNode response =
                    breakGlass(otherDoctorToken, "Benh nhan cap cuu ngoai gio", 200);

            assertThat(response.get("expiresAt").asText()).isNotBlank();
            assertThat(response.get("notice").asText()).contains("notified");
        }

        @Test
        void lastsTheConfiguredDuration() throws Exception {
            breakGlass(otherDoctorToken, "Benh nhan cap cuu ngoai gio", 200);

            BreakGlassGrant grant = grantRepository
                    .findActive(otherDoctorId, UUID.fromString(patientId), Instant.now())
                    .orElseThrow();

            long hours = java.time.Duration.between(
                    grant.getGrantedAt(), grant.getExpiresAt()).toHours();
            assertThat(hours).isEqualTo(2);
        }

        @Test
        void refusesABlankReason() throws Exception {
            breakGlass(otherDoctorToken, "   ", 400);

            // Refused means refused: still no access.
            getPatient(otherDoctorToken, 403);
        }

        @Test
        void refusesAReasonTooShortToMeanAnything() throws Exception {
            breakGlass(otherDoctorToken, "abc", 400);

            getPatient(otherDoctorToken, 403);
        }

        @Test
        void storesTheReason() throws Exception {
            String reason = "Benh nhan hon me, can tra cuu tien su di ung ngay";
            breakGlass(otherDoctorToken, reason, 200);

            String stored = jdbcTemplate.queryForObject(
                    "select reason from break_glass_grant where staff_id = ?::uuid",
                    String.class, otherDoctorId);
            assertThat(stored).isEqualTo(reason);
        }

        /**
         * Requirement 8.5. Expiry is evaluated by the lookup, so winding the
         * expiry back is enough to close the door; nothing has to run.
         */
        @Test
        void stopsWorkingOnceItExpires() throws Exception {
            breakGlass(otherDoctorToken, "Benh nhan cap cuu, can xem ngay tien su", 200);
            getPatient(otherDoctorToken, 200);

            // Both ends move: the window check forbids an expiry before the
            // grant, so winding only the expiry back would violate it. This is
            // what the grant would look like three hours after it was issued.
            jdbcTemplate.update("""
                    update break_glass_grant
                       set granted_at = now() - interval '3 hours',
                           expires_at = now() - interval '1 hour'
                     where staff_id = ?::uuid
                    """, otherDoctorId);

            getPatient(otherDoctorToken, 403);
        }

        @Test
        void stopsWorkingOnceAnAdminRevokesIt() throws Exception {
            breakGlass(otherDoctorToken, "Benh nhan cap cuu, can xem ngay tien su", 200);
            getPatient(otherDoctorToken, 200);

            jdbcTemplate.update(
                    "update break_glass_grant set revoked_at = now() where staff_id = ?::uuid",
                    otherDoctorId);

            getPatient(otherDoctorToken, 403);
        }

        @Test
        void doesNotOpenOtherPatients() throws Exception {
            breakGlass(otherDoctorToken, "Benh nhan cap cuu, can xem ngay tien su", 200);

            String another = createPatient(uniqueName());

            mockMvc.perform(get("/api/patients/" + another)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherDoctorToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        void doesNotOpenTheRecordForAnotherDoctor() throws Exception {
            breakGlass(otherDoctorToken, "Benh nhan cap cuu, can xem ngay tien su", 200);

            UUID thirdDoctorId = ensureStaff("bs-thu-ba", "DOCTOR");
            assertThat(thirdDoctorId).isNotNull();
            String thirdToken = logIn("bs-thu-ba");

            mockMvc.perform(get("/api/patients/" + patientId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + thirdToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        void bringsThePatientIntoTheDoctorsList() throws Exception {
            breakGlass(otherDoctorToken, "Benh nhan cap cuu, can xem ngay tien su", 200);

            String body = listByName(otherDoctorToken, patientName).toString();

            assertThat(body).contains(patientId);
        }

        @Test
        void isRefusedForAPatientThatDoesNotExist() throws Exception {
            mockMvc.perform(post("/api/patients/" + UUID.randomUUID() + "/break-glass")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherDoctorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new BreakGlassRequest("Benh nhan cap cuu, can xem tien su"))))
                    .andExpect(status().isNotFound());
        }
    }

    // ---------- what the trail shows ----------

    @Nested
    class AuditTrail {

        private long countAudit(String action) {
            return jdbcTemplate.queryForObject(
                    "select count(*) from audit_log where action = ? and entity_id = ?::uuid",
                    Long.class, action, patientId);
        }

        @Test
        void recordsBreakGlassUnderItsOwnAction() throws Exception {
            long before = countAudit("BREAK_GLASS");

            breakGlass(otherDoctorToken, "Benh nhan cap cuu, can xem ngay tien su", 200);

            assertThat(countAudit("BREAK_GLASS")).isEqualTo(before + 1);
        }

        /**
         * Its own action rather than a severity column: the action is part of what
         * the audit hash covers, so it cannot be downgraded later without breaking
         * the chain.
         */
        @Test
        void theBreakGlassEntryIsChainedLikeAnyOther() throws Exception {
            breakGlass(otherDoctorToken, "Benh nhan cap cuu, can xem ngay tien su", 200);

            String entryHash = jdbcTemplate.queryForObject("""
                    select entry_hash from audit_log
                     where action = 'BREAK_GLASS' and entity_id = ?::uuid
                     order by id desc limit 1
                    """, String.class, patientId);

            assertThat(entryHash).isNotBlank();
        }

        /** Requirement 8.7: an admin reading a patient record is always recorded. */
        @Test
        void recordsEveryAdminRead() throws Exception {
            long before = countAudit("VIEW");

            getPatient(adminToken, 200);

            assertThat(countAudit("VIEW")).isEqualTo(before + 1);
        }

        /**
         * A doctor reading their own patient is the system working. Recording
         * every such read would bury the entries that matter.
         */
        @Test
        void doesNotRecordARoutineReadByTheTreatingDoctor() throws Exception {
            long before = countAudit("VIEW");

            getPatient(treatingDoctorToken, 200);

            assertThat(countAudit("VIEW")).isEqualTo(before);
        }

        /** A read reached only through a grant is worth recording on its own. */
        @Test
        void recordsAReadReachedThroughAGrant() throws Exception {
            breakGlass(otherDoctorToken, "Benh nhan cap cuu, can xem ngay tien su", 200);
            long before = countAudit("VIEW");

            getPatient(otherDoctorToken, 200);

            assertThat(countAudit("VIEW")).isEqualTo(before + 1);
        }

        @Test
        void doesNotRecordTheReasonInTheTrail() throws Exception {
            String reason = "Benh nhan hon me, tien su di ung chua ro";
            breakGlass(otherDoctorToken, reason, 200);

            Long leaked = jdbcTemplate.queryForObject(
                    "select count(*) from audit_log where detail::text like ?",
                    Long.class, "%" + reason + "%");

            // The reason lives on the grant. Copying it into the trail would put a
            // sentence about the patient in a second place with different
            // protections, that outlives the record it describes.
            assertThat(leaked).isZero();
        }
    }
}
