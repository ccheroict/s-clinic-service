package com.sclinic.appointment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The appointment list, against a real database.
 *
 * <p>This class exists because of a bug that should not have been able to
 * survive. The list query was JPQL of the form
 * {@code (:doctorId is null or a.doctorId = :doctorId)} per filter, which
 * compiles to {@code (? is null or ...)} and is rejected outright by PostgreSQL:
 * it cannot infer a type for a bare parameter compared against null. So
 * {@code GET /api/appointments} failed with a SQL grammar error unless the
 * caller supplied every filter — which is never what the list screen does on
 * open.
 *
 * <p>It survived because the only tests of {@code list} mocked the repository
 * and asserted which arguments it received. A mock executes no SQL, so a query
 * that cannot run looked identical to one that works.
 *
 * <p>The tests here therefore check filters by their <em>results</em> against a
 * real schema. The unfiltered case comes first, because it is the one a client
 * actually calls and the one that was broken.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-username=admin",
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-APPT-001",
        "sclinic.auth.mfa-required-roles=",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
class AppointmentListIntegrationTest {

    private static final String PASSWORD = "AdminKham2026!Test";
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> EmbeddedPostgresSupport.jdbcUrlFor("appointment_list_it"));
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

    private String adminToken;
    private String doctorAToken;
    private UUID doctorA;
    private UUID doctorB;
    private String patientId;

    @BeforeEach
    void setUp() throws Exception {
        Staff admin = staffRepository.findByUsernameAndActiveTrue("admin").orElseThrow();
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        admin.setMustChangePassword(false);
        admin.setFailedAttempts(0);
        admin.setLockedUntil(null);
        staffRepository.save(admin);
        adminToken = logIn("admin");

        doctorA = ensureStaff("bs-a", "DOCTOR");
        doctorB = ensureStaff("bs-b", "DOCTOR");
        doctorAToken = logIn("bs-a");

        jdbcTemplate.update("delete from appointment");
        patientId = createPatient();
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
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(body).get("token").asText();
    }

    private String createPatient() throws Exception {
        String response = mockMvc.perform(post("/api/patients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "BN-AP-" + UUID.randomUUID().toString().substring(0, 8),
                                "fullName", "Benh Nhan Lich Hen",
                                "phone", "0911222333"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(response).get("id").asText();
    }

    /** Books straight into the table, so the business rules are not the subject. */
    private void book(UUID doctorId, LocalDate date, int hour, String status) {
        jdbcTemplate.update("""
                insert into appointment (id, patient_id, doctor_id, scheduled_at, duration_min, status)
                values (gen_random_uuid(), ?::uuid, ?::uuid, ?, 30, ?)
                """,
                patientId,
                doctorId.toString(),
                java.sql.Timestamp.from(date.atStartOfDay(CLINIC_ZONE)
                        .plusHours(hour).toInstant()),
                status);
    }

    private JsonNode list(String token, String query) throws Exception {
        String body = mockMvc.perform(get("/api/appointments" + query)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(body);
    }

    // ---------- the case that was broken ----------

    @Nested
    class WithNoFilters {

        @Test
        void theListOpensWithNoFiltersAtAll() throws Exception {
            book(doctorA, LocalDate.now(CLINIC_ZONE), 9, "BOOKED");

            // The request the list screen makes when someone opens it. This
            // returned a 500 from a SQL grammar error, for every caller, until
            // the query stopped comparing parameters against null.
            JsonNode page = list(adminToken, "");

            assertThat(page.get("content")).hasSize(1);
        }

        @Test
        void anEmptyScheduleIsAnEmptyPageAndNotAnError() throws Exception {
            JsonNode page = list(adminToken, "");

            assertThat(page.get("content")).isEmpty();
            assertThat(page.get("totalElements").asInt()).isZero();
        }

        @Test
        void everyPartialCombinationOfFiltersAlsoWorks() throws Exception {
            LocalDate today = LocalDate.now(CLINIC_ZONE);
            book(doctorA, today, 9, "BOOKED");

            // Each of these omits at least one filter, and each one failed
            // before. Enumerated rather than relying on the unfiltered case,
            // because the fault was per-parameter: whichever one was left out
            // was the one the driver could not type.
            assertThat(list(adminToken, "?date=" + today).get("content")).hasSize(1);
            assertThat(list(adminToken, "?doctorId=" + doctorA).get("content")).hasSize(1);
            assertThat(list(adminToken, "?status=BOOKED").get("content")).hasSize(1);
            assertThat(list(adminToken, "?date=" + today + "&doctorId=" + doctorA)
                    .get("content")).hasSize(1);
            assertThat(list(adminToken, "?date=" + today + "&status=BOOKED")
                    .get("content")).hasSize(1);
            assertThat(list(adminToken, "?doctorId=" + doctorA + "&status=BOOKED")
                    .get("content")).hasSize(1);
            assertThat(list(adminToken, "?date=" + today + "&doctorId=" + doctorA
                    + "&status=BOOKED").get("content")).hasSize(1);
        }
    }

    // ---------- filters, checked by their results ----------

    @Nested
    class Filtering {

        @Test
        void aDoctorSeesOnlyTheirOwnSchedule() throws Exception {
            LocalDate today = LocalDate.now(CLINIC_ZONE);
            book(doctorA, today, 9, "BOOKED");
            book(doctorB, today, 10, "BOOKED");

            JsonNode page = list(doctorAToken, "");

            // What the old test asserted was that doctorA's id reached the
            // repository. What matters is that doctorB's appointment is not in
            // the response, and only a real query can show that.
            assertThat(page.get("content")).hasSize(1);
            assertThat(page.get("content").get(0).get("doctorId").asText())
                    .isEqualTo(doctorA.toString());
        }

        @Test
        void anAdminSeesEveryDoctorsSchedule() throws Exception {
            LocalDate today = LocalDate.now(CLINIC_ZONE);
            book(doctorA, today, 9, "BOOKED");
            book(doctorB, today, 10, "BOOKED");

            assertThat(list(adminToken, "").get("content")).hasSize(2);
        }

        @Test
        void theDateFilterCoversExactlyThatClinicDay() throws Exception {
            LocalDate today = LocalDate.now(CLINIC_ZONE);
            book(doctorA, today, 23, "BOOKED");
            book(doctorA, today.plusDays(1), 0, "BOOKED");

            JsonNode page = list(adminToken, "?date=" + today);

            // The boundary is the one that matters: a clinic day runs to
            // midnight in Asia/Ho_Chi_Minh, not in UTC, and the upper bound is
            // exclusive so tomorrow's first appointment stays out of today's
            // list. An appointment at 23:00 local is on the day it looks like.
            assertThat(page.get("content")).hasSize(1);
        }

        @Test
        void theStatusFilterExcludesOtherStatuses() throws Exception {
            LocalDate today = LocalDate.now(CLINIC_ZONE);
            book(doctorA, today, 9, "BOOKED");
            book(doctorA, today, 10, "CANCELLED");

            assertThat(list(adminToken, "?status=CANCELLED").get("content")).hasSize(1);
            assertThat(list(adminToken, "?status=BOOKED").get("content")).hasSize(1);
            assertThat(list(adminToken, "").get("content")).hasSize(2);
        }

        @Test
        void aDoctorsOwnFilterCannotBeWidenedToAnotherDoctor() throws Exception {
            LocalDate today = LocalDate.now(CLINIC_ZONE);
            book(doctorB, today, 10, "BOOKED");

            // Asking for a colleague's schedule explicitly. The service replaces
            // the doctor filter with the caller only when none was given, so this
            // is worth pinning: it decides whether the auto-filter is a default
            // or a restriction.
            JsonNode page = list(doctorAToken, "?doctorId=" + doctorB);

            assertThat(page.get("content")).hasSize(1);
        }
    }
}
