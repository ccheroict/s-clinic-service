package com.sclinic.patient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclinic.patient.dto.PatientDeleteRequest;
import com.sclinic.retention.RetentionPolicy;
import com.sclinic.retention.RetentionPolicyRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Withdrawing a patient record, against a real database.
 *
 * <p>The property under test is that a clinic cannot lose a medical record: the
 * business API makes a withdrawn record disappear, but the row and its history
 * stay, and no endpoint anywhere removes one.
 *
 * <p>Not {@code @Transactional}: the retention date is derived from values the
 * database generates, and the constraints under test are enforced by the database
 * on commit.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-username=admin",
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-SOFTDEL-001",
        "sclinic.auth.mfa-required-roles=",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
class SoftDeleteIntegrationTest {

    private static final String ADMIN_PASSWORD = "AdminKham2026!Test";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> EmbeddedPostgresSupport.jdbcUrlFor("softdelete_it"));
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
    RetentionPolicyRepository retentionPolicyRepository;

    @Autowired
    PatientRepository patientRepository;

    @Autowired
    PatientService patientService;

    private String token;

    @BeforeEach
    void logInAsAdmin() throws Exception {
        Staff admin = staffRepository.findByUsernameAndActiveTrue("admin").orElseThrow();
        admin.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
        admin.setMustChangePassword(false);
        admin.setFailedAttempts(0);
        admin.setLockedUntil(null);
        staffRepository.save(admin);

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("admin", ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = objectMapper.readTree(body).get("token").asText();

        retentionPolicyRepository.deleteAll();
    }

    // ---------- helpers ----------

    private String createPatient(String code, String name) throws Exception {
        String body = mockMvc.perform(post("/api/patients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "fullName", name,
                                "phone", "0900000000"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void withdraw(String patientId, String reason) throws Exception {
        mockMvc.perform(delete("/api/patients/" + patientId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PatientDeleteRequest(reason))))
                .andExpect(status().isNoContent());
    }

    private JsonNode listPatients(String keyword) throws Exception {
        String uri = UriComponentsBuilder.fromPath("/api/patients")
                .queryParamIfPresent("q", java.util.Optional.ofNullable(keyword))
                .build().toUriString();

        String body = mockMvc.perform(get(uri)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void configurePolicy(int years, Integer yearsAfterDeath) {
        RetentionPolicy policy = new RetentionPolicy();
        policy.setRecordType("patient");
        policy.setRetentionYears(years);
        policy.setRetentionYearsAfterDeath(yearsAfterDeath);
        policy.setLegalBasis("Test fixture, not a legal citation");
        retentionPolicyRepository.save(policy);
    }

    // ---------- the record survives ----------

    @Nested
    class NothingIsEverRemoved {

        @Test
        void theRowStaysAfterWithdrawal() throws Exception {
            String id = createPatient("BN-KEEP-1", "Nguyen Van Keep");

            withdraw(id, "Nhap trung ban ghi");

            Long rows = jdbcTemplate.queryForObject(
                    "select count(*) from patient where id = ?::uuid", Long.class, id);
            assertThat(rows).isEqualTo(1);
        }

        @Test
        void recordsWhoWithdrewItAndWhy() throws Exception {
            String id = createPatient("BN-KEEP-2", "Nguyen Van Why");

            withdraw(id, "Benh nhan trung lap");

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "select deleted_at, deleted_by, delete_reason from patient where id = ?::uuid", id);

            assertThat(row.get("deleted_at")).isNotNull();
            assertThat(row.get("deleted_by")).isNotNull();
            assertThat(row.get("delete_reason")).isEqualTo("Benh nhan trung lap");
        }

        /**
         * The reason is not optional, and the database says so independently of
         * the request validation. Belt and braces, because this is the field that
         * makes a withdrawal reviewable afterwards.
         */
        @Test
        void theDatabaseRefusesAWithdrawalWithNoReason() throws Exception {
            String id = createPatient("BN-KEEP-3", "Nguyen Van NoReason");

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "update patient set deleted_at = now(), deleted_by = ?::uuid where id = ?::uuid",
                    adminId(), id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void theDatabaseRefusesAWithdrawalWithABlankReason() throws Exception {
            String id = createPatient("BN-KEEP-4", "Nguyen Van Blank");

            assertThatThrownBy(() -> jdbcTemplate.update("""
                            update patient
                               set deleted_at = now(), deleted_by = ?::uuid, delete_reason = '   '
                             where id = ?::uuid
                            """, adminId(), id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void theDatabaseRefusesAWithdrawalWithNoActor() throws Exception {
            String id = createPatient("BN-KEEP-5", "Nguyen Van NoActor");

            assertThatThrownBy(() -> jdbcTemplate.update("""
                            update patient
                               set deleted_at = now(), delete_reason = 'khong ro ai xoa'
                             where id = ?::uuid
                            """, id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        private String adminId() {
            return staffRepository.findByUsernameAndActiveTrue("admin")
                    .orElseThrow().getId().toString();
        }
    }

    // ---------- withdrawn records leave the business API ----------

    @Nested
    class InvisibleToTheBusinessApi {

        @Test
        void disappearsFromTheList() throws Exception {
            String id = createPatient("BN-GONE-1", "Nguyen Van Gone");
            assertThat(listPatients(null).get("content").toString()).contains("BN-GONE-1");

            withdraw(id, "Nhap trung");

            assertThat(listPatients(null).get("content").toString()).doesNotContain("BN-GONE-1");
        }

        @Test
        void disappearsFromSearchByName() throws Exception {
            String id = createPatient("BN-GONE-2", "Tran Thi Searchable");
            assertThat(listPatients("Searchable").get("totalElements").asLong()).isEqualTo(1);

            withdraw(id, "Nhap trung");

            assertThat(listPatients("Searchable").get("totalElements").asLong()).isZero();
        }

        @Test
        void disappearsFromSearchByPhone() throws Exception {
            String id = createPatient("BN-GONE-3", "Tran Thi Phone");
            assertThat(listPatients("0900000000").get("totalElements").asLong())
                    .isGreaterThan(0);
            long before = listPatients("0900000000").get("totalElements").asLong();

            withdraw(id, "Nhap trung");

            assertThat(listPatients("0900000000").get("totalElements").asLong())
                    .isEqualTo(before - 1);
        }

        @Test
        void canNoLongerBeFetchedById() throws Exception {
            String id = createPatient("BN-GONE-4", "Nguyen Van Fetch");

            withdraw(id, "Nhap trung");

            mockMvc.perform(get("/api/patients/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        void canNoLongerBeUpdated() throws Exception {
            String id = createPatient("BN-GONE-5", "Nguyen Van Update");

            withdraw(id, "Nhap trung");

            mockMvc.perform(put("/api/patients/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "fullName", "Ten Moi"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        void cannotBeWithdrawnTwice() throws Exception {
            String id = createPatient("BN-GONE-6", "Nguyen Van Twice");
            withdraw(id, "Lan dau");

            mockMvc.perform(delete("/api/patients/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new PatientDeleteRequest("Lan hai"))))
                    .andExpect(status().isNotFound());
        }
    }

    // ---------- the reason is required at the API too ----------

    @Nested
    class ReasonRequired {

        @Test
        void refusesAWithdrawalWithNoBody() throws Exception {
            String id = createPatient("BN-REASON-1", "Nguyen Van NoBody");

            mockMvc.perform(delete("/api/patients/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void refusesABlankReason() throws Exception {
            String id = createPatient("BN-REASON-2", "Nguyen Van Blank");

            mockMvc.perform(delete("/api/patients/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new PatientDeleteRequest("   "))))
                    .andExpect(status().isBadRequest());

            // Still there, because the request was refused.
            mockMvc.perform(get("/api/patients/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
        }

        @Test
        void refusesWithdrawalByANonAdmin() throws Exception {
            String id = createPatient("BN-REASON-3", "Nguyen Van Receptionist");

            Staff receptionist = new Staff();
            receptionist.setUsername("letan-softdel");
            receptionist.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
            receptionist.setFullName("Le Tan");
            receptionist.setRole("RECEPTIONIST");
            receptionist.setActive(true);
            staffRepository.save(receptionist);

            String body = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest("letan-softdel", ADMIN_PASSWORD))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String receptionistToken = objectMapper.readTree(body).get("token").asText();

            mockMvc.perform(delete("/api/patients/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new PatientDeleteRequest("khong duoc phep"))))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------- retention ----------

    @Nested
    class Retention {

        /**
         * The safety default. With no policy configured the system cannot work out
         * a retention date, so it stores none, and nothing is ever judged past its
         * retention period.
         */
        @Test
        void storesNoRetentionDateWhileNoPolicyIsConfigured() throws Exception {
            String id = createPatient("BN-RET-1", "Nguyen Van NoPolicy");

            withdraw(id, "Nhap trung");

            assertThat(jdbcTemplate.queryForObject(
                    "select retention_until from patient where id = ?::uuid", LocalDate.class, id))
                    .isNull();
            assertThat(patientService.findEligibleForDestruction()).isEmpty();
        }

        @Test
        void computesTheDateFromTheConfiguredPolicy() throws Exception {
            configurePolicy(10, null);
            String id = createPatient("BN-RET-2", "Nguyen Van Policy");

            withdraw(id, "Nhap trung");

            LocalDate retentionUntil = jdbcTemplate.queryForObject(
                    "select retention_until from patient where id = ?::uuid", LocalDate.class, id);
            assertThat(retentionUntil).isEqualTo(LocalDate.now().plusYears(10));
        }

        @Test
        void exposesTheRetentionStateOnTheRecord() throws Exception {
            configurePolicy(10, null);
            String id = createPatient("BN-RET-3", "Nguyen Van Exposed");

            mockMvc.perform(get("/api/patients/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.retentionUntil").isNotEmpty())
                    .andExpect(jsonPath("$.legalHold").value(false));
        }

        @Test
        void recomputesFromTheDateOfDeath() throws Exception {
            configurePolicy(10, 20);
            String id = createPatient("BN-RET-4", "Nguyen Van Deceased");

            mockMvc.perform(put("/api/patients/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "fullName", "Nguyen Van Deceased",
                                    "deceasedOn", "2026-01-10"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.retentionUntil").value("2046-01-10"));
        }

        @Test
        void refusesADateOfDeathInTheFuture() throws Exception {
            String id = createPatient("BN-RET-5", "Nguyen Van Future");

            mockMvc.perform(put("/api/patients/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "fullName", "Nguyen Van Future",
                                    "deceasedOn", LocalDate.now().plusDays(1).toString()))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void listsARecordWhoseRetentionPeriodHasRunOut() throws Exception {
            configurePolicy(10, null);
            String id = createPatient("BN-RET-6", "Nguyen Van Expired");
            withdraw(id, "Nhap trung");

            // Wind the retention date back past today, which is what the passage
            // of ten years would do.
            jdbcTemplate.update(
                    "update patient set retention_until = ?::date where id = ?::uuid",
                    LocalDate.now().minusDays(1).toString(), id);

            assertThat(patientService.findEligibleForDestruction())
                    .contains(java.util.UUID.fromString(id));
        }

        /**
         * Requirement 6.5: a legal hold outranks the retention period. An open
         * complaint or inspection must keep a record even once its statutory
         * period has run out.
         */
        @Test
        void aLegalHoldKeepsARecordThatIsOtherwiseEligible() throws Exception {
            configurePolicy(10, null);
            String id = createPatient("BN-RET-7", "Nguyen Van Hold");
            withdraw(id, "Nhap trung");

            jdbcTemplate.update("""
                    update patient
                       set retention_until = ?::date, legal_hold = true
                     where id = ?::uuid
                    """, LocalDate.now().minusYears(5).toString(), id);

            assertThat(patientService.findEligibleForDestruction())
                    .doesNotContain(java.util.UUID.fromString(id));
        }

        @Test
        void anActiveRecordIsNeverEligibleEvenPastItsRetentionDate() throws Exception {
            configurePolicy(10, null);
            String id = createPatient("BN-RET-8", "Nguyen Van Active");

            jdbcTemplate.update(
                    "update patient set retention_until = ?::date where id = ?::uuid",
                    LocalDate.now().minusYears(5).toString(), id);

            // Still in use, so the retention period is beside the point.
            assertThat(patientService.findEligibleForDestruction())
                    .doesNotContain(java.util.UUID.fromString(id));
        }
    }

    // ---------- appointments still resolve their patient ----------

    /**
     * Withdrawal is scoped to the patient API on purpose. An appointment that
     * already happened still has to be able to name its patient, which is why
     * withdrawn records are excluded by naming them per query rather than by a
     * filter that would hide them from everything at once.
     */
    @Test
    void aWithdrawnPatientIsStillReachableForRecordsThatReferenceThem() throws Exception {
        String id = createPatient("BN-REF-1", "Nguyen Van Referenced");
        withdraw(id, "Nhap trung");

        assertThat(patientRepository.findById(java.util.UUID.fromString(id))).isPresent();
        assertThat(patientRepository.findByIdAndDeletedAtIsNull(java.util.UUID.fromString(id)))
                .isEmpty();
    }
}
