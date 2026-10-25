package com.sclinic.encounter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclinic.catalogue.CatalogIcd10;
import com.sclinic.catalogue.CatalogIcd10Repository;
import com.sclinic.consent.ConsentCollectionMethod;
import com.sclinic.consent.ConsentService;
import com.sclinic.consent.ConsentTypeCode;
import com.sclinic.consent.ConsentTypeRepository;
import com.sclinic.consent.dto.ConsentGrantRequest;
import com.sclinic.consent.dto.ConsentVersionRequest;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The encounter record, against a real database.
 *
 * <p>Covers the three things requirement 13 asks for that only a real schema can
 * show: that creating a record from an appointment moves that appointment on,
 * that a record cannot carry two primary diagnoses, and that the diagnosis text
 * on a signed record does not change when the catalogue behind it does.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-username=admin",
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-ENC-001",
        "sclinic.auth.mfa-required-roles=",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
class EncounterIntegrationTest {

    private static final String PASSWORD = "AdminKham2026!Test";
    private static final String SENSITIVITY_NOTICE =
            "Dữ liệu sức khỏe của bạn là dữ liệu cá nhân nhạy cảm theo NĐ 13/2023.";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> EmbeddedPostgresSupport.jdbcUrlFor("encounter_it"));
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
    CatalogIcd10Repository icd10Repository;

    @Autowired
    ConsentService consentService;

    @Autowired
    ConsentTypeRepository consentTypes;

    @Autowired
    EncounterRepository encounterRepository;

    @Autowired
    EncounterDiagnosisRepository diagnosisRepository;

    private String adminToken;
    private String doctorToken;
    private UUID doctorId;
    private String patientId;
    private UUID patientUuid;

    @BeforeEach
    void setUp() throws Exception {
        Staff admin = staffRepository.findByUsernameAndActiveTrue("admin").orElseThrow();
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        admin.setMustChangePassword(false);
        admin.setFailedAttempts(0);
        admin.setLockedUntil(null);
        staffRepository.save(admin);
        adminToken = logIn("admin");

        doctorId = ensureStaff("bs-da-lieu", "DOCTOR");
        doctorToken = logIn("bs-da-lieu");

        jdbcTemplate.update("delete from encounter_diagnosis");
        jdbcTemplate.update("delete from encounter");
        icd10Repository.deleteAllInBatch();
        seedIcd10("L20.9", "Viêm da cơ địa, không đặc hiệu");
        seedIcd10("L40.0", "Vảy nến thể mảng");

        publishConsentWording();
        patientId = createPatient();
        patientUuid = UUID.fromString(patientId);
        bookAppointment(patientId, doctorId, "BOOKED");
        grantTreatmentConsent(patientUuid);
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

    private void seedIcd10(String code, String name) {
        CatalogIcd10 entry = new CatalogIcd10();
        entry.setCode(code);
        entry.setName(name);
        entry.setActive(true);
        icd10Repository.save(entry);
    }

    /** V14 ships the consent types without wording, so a test has to publish it. */
    private void publishConsentWording() {
        for (ConsentTypeCode type : List.of(ConsentTypeCode.TREATMENT,
                ConsentTypeCode.SENSITIVE_DATA)) {
            if (consentService.hasConsent(UUID.randomUUID(), type)) {
                continue;
            }
            boolean sensitive = consentTypes.findByCode(type.name()).orElseThrow().isSensitive();
            try {
                consentService.currentVersion(type);
            } catch (RuntimeException notPublishedYet) {
                consentService.publishVersion(type, new ConsentVersionRequest(
                        "Nội dung đồng ý dùng cho kiểm thử",
                        "Phục vụ khám và điều trị",
                        sensitive ? SENSITIVITY_NOTICE : null,
                        null));
            }
        }
    }

    private void grantTreatmentConsent(UUID patient) {
        for (ConsentTypeCode type : List.of(ConsentTypeCode.TREATMENT,
                ConsentTypeCode.SENSITIVE_DATA)) {
            consentService.grant(patient,
                    new ConsentGrantRequest(type, ConsentCollectionMethod.PAPER, null, null),
                    doctorId);
        }
    }

    private String createPatient() throws Exception {
        String response = mockMvc.perform(post("/api/patients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "BN-EN-" + UUID.randomUUID().toString().substring(0, 8),
                                "fullName", "Benh Nhan Kham",
                                "phone", "0911222333"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(response).get("id").asText();
    }

    private UUID bookAppointment(String patient, UUID doctor, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into appointment (id, patient_id, doctor_id, scheduled_at, duration_min, status)
                values (?::uuid, ?::uuid, ?::uuid, now(), 30, ?)
                """, id.toString(), patient, doctor.toString(), status);
        return id;
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "select status from appointment where id = ?::uuid", String.class,
                appointmentId.toString());
    }

    private Map<String, Object> encounterBody(UUID appointmentId,
                                              List<Map<String, Object>> diagnoses) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        if (appointmentId != null) {
            body.put("appointmentId", appointmentId.toString());
        }
        body.put("reason", "Ngứa, mẩn đỏ hai cẳng tay");
        body.put("clinicalData", Map.of(
                "lesionDescription", "Mảng đỏ bong vảy",
                "severity", "MODERATE",
                "itchScore", 6));
        if (diagnoses != null) {
            body.put("diagnoses", diagnoses);
        }
        return body;
    }

    private Map<String, Object> diagnosis(String code, boolean primary) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("icd10Code", code);
        entry.put("primary", primary);
        return entry;
    }

    private JsonNode create(String token, Map<String, Object> body, int expectedStatus)
            throws Exception {
        String response = mockMvc.perform(post("/api/encounters")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return response.isEmpty() ? null : objectMapper.readTree(response);
    }

    // ---------- requirement 13.6 ----------

    @Nested
    class AppointmentLink {

        @Test
        void creatingARecordFromAnAppointmentStartsIt() throws Exception {
            UUID appointment = bookAppointment(patientId, doctorId, "ARRIVED");

            create(doctorToken, encounterBody(appointment, null), 201);

            // The appointment moves itself, so the schedule reflects what is
            // happening without anyone having to remember to update it.
            assertThat(appointmentStatus(appointment)).isEqualTo("IN_PROGRESS");
        }

        @Test
        void aWalkInNeedsNoAppointment() throws Exception {
            JsonNode created = create(doctorToken, encounterBody(null, null), 201);

            // Requiring one would mean staff inventing an appointment
            // retrospectively so the record could be written, which puts a
            // fiction in the schedule to satisfy a constraint.
            assertThat(created.get("appointmentId").isNull()).isTrue();
        }

        @Test
        void anAppointmentThatCannotLegallyStartIsRefused() throws Exception {
            UUID cancelled = bookAppointment(patientId, doctorId, "CANCELLED");

            // The state machine owns this rule. A record written against a
            // cancelled appointment is something to notice, not to tidy away by
            // dragging the appointment back to life.
            create(doctorToken, encounterBody(cancelled, null), 400);
        }

        @Test
        void anAppointmentAlreadyInProgressIsLeftAlone() throws Exception {
            UUID appointment = bookAppointment(patientId, doctorId, "IN_PROGRESS");

            create(doctorToken, encounterBody(appointment, null), 201);

            assertThat(appointmentStatus(appointment)).isEqualTo("IN_PROGRESS");
        }

        @Test
        void oneAppointmentCannotHaveTwoRecords() throws Exception {
            UUID appointment = bookAppointment(patientId, doctorId, "ARRIVED");
            create(doctorToken, encounterBody(appointment, null), 201);

            // One visit, one record. Two would make "what happened at this
            // appointment" a question with two answers.
            JsonNode conflict = create(doctorToken, encounterBody(appointment, null), 409);

            // And the refusal hands back the record that already exists, because
            // the usual cause is a double-clicked button and the caller wants to
            // carry on writing the record they already created.
            assertThat(conflict.get("existingEncounterId").isNull()).isFalse();
        }
    }

    // ---------- requirement 13.2 ----------

    @Nested
    class Diagnoses {

        @Test
        void aRecordCarriesSeveralDiagnosesWithOneMarkedPrimary() throws Exception {
            JsonNode created = create(doctorToken, encounterBody(null,
                    List.of(diagnosis("L20.9", true), diagnosis("L40.0", false))), 201);

            assertThat(created.get("diagnoses")).hasSize(2);
            assertThat(created.get("diagnoses").get(0).get("primary").asBoolean()).isTrue();
        }

        @Test
        void twoPrimaryDiagnosesAreRefused() throws Exception {
            create(doctorToken, encounterBody(null,
                    List.of(diagnosis("L20.9", true), diagnosis("L40.0", true))), 400);
        }

        @Test
        void theDatabaseRefusesASecondPrimaryEvenWhenTheServiceIsBypassed()
                throws Exception {
            JsonNode created = create(doctorToken, encounterBody(null,
                    List.of(diagnosis("L20.9", true))), 201);
            String encounterId = created.get("id").asText();
            UUID otherCode = icd10Repository.findByCode("L40.0").orElseThrow().getId();

            // The service check gives the better message; the partial unique
            // index is what makes the rule hold for an import or a fix-up script.
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    insert into encounter_diagnosis
                        (id, encounter_id, icd10_id, icd10_code, icd10_name, is_primary)
                    values (gen_random_uuid(), ?::uuid, ?::uuid, 'L40.0', 'Vay nen', true)
                    """, encounterId, otherCode.toString()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void aDraftMayHaveNoDiagnosisYet() throws Exception {
            // Typing a record passes through having none. A constraint forbidding
            // that would make the first diagnosis entered automatically the
            // primary one, with a two-step dance to correct it.
            JsonNode created = create(doctorToken, encounterBody(null, List.of()), 201);

            assertThat(created.get("diagnoses")).isEmpty();
        }

        @Test
        void anIcd10CodeOutsideTheCatalogueIsRefused() throws Exception {
            // The alternative is inventing catalogue entries from whatever was
            // typed, which is how a register ends up with four spellings of one
            // diagnosis and statistics that mean nothing.
            create(doctorToken, encounterBody(null, List.of(diagnosis("KHONG-CO", true))), 404);
        }

        @Test
        void theDiagnosisTextIsFrozenWhenTheCatalogueIsLaterCorrected()
                throws Exception {
            JsonNode created = create(doctorToken, encounterBody(null,
                    List.of(diagnosis("L20.9", true))), 201);
            String encounterId = created.get("id").asText();

            // The Ministry reissues the Vietnamese wording of the code.
            CatalogIcd10 code = icd10Repository.findByCode("L20.9").orElseThrow();
            code.setName("Viêm da cơ địa (tên đã sửa)");
            icd10Repository.save(code);

            String body = mockMvc.perform(get("/api/encounters/" + encounterId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            // The record keeps saying what the doctor recorded. Reading the name
            // through the reference would let a catalogue correction rewrite
            // history, which is the same reason a consent record freezes its
            // purpose.
            assertThat(objectMapper.readTree(body).get("diagnoses").get(0)
                    .get("icd10Name").asText()).doesNotContain("đã sửa");
        }

        @Test
        void changingTheDiagnosesReplacesThemWholesale() throws Exception {
            JsonNode created = create(doctorToken, encounterBody(null,
                    List.of(diagnosis("L20.9", true), diagnosis("L40.0", false))), 201);
            String encounterId = created.get("id").asText();

            Map<String, Object> update = new HashMap<>();
            update.put("reason", "Đã sửa lại");
            update.put("diagnoses", List.of(diagnosis("L40.0", true)));

            String body = mockMvc.perform(put("/api/encounters/" + encounterId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(update)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            JsonNode diagnoses = objectMapper.readTree(body).get("diagnoses");

            // Including moving the primary flag to a diagnosis that was secondary,
            // which a diff-based update would collide with the unique index over.
            assertThat(diagnoses).hasSize(1);
            assertThat(diagnoses.get(0).get("icd10Code").asText()).isEqualTo("L40.0");
            assertThat(diagnoses.get(0).get("primary").asBoolean()).isTrue();
        }
    }

    // ---------- clinical data ----------

    @Nested
    class ClinicalData {

        @Test
        void theRecordStoresWhichSchemaVersionValidatedIt() throws Exception {
            JsonNode created = create(doctorToken, encounterBody(null, null), 201);

            assertThat(created.get("clinicalSpecialtyCode").asText()).isEqualTo("DERMATOLOGY");
            assertThat(created.get("clinicalSchemaVersion").asInt()).isEqualTo(1);
        }

        @Test
        void dataThatDoesNotMatchTheSchemaIsRefusedWithEveryFailureListed()
                throws Exception {
            Map<String, Object> body = encounterBody(null, null);
            body.put("clinicalData", Map.of(
                    "severity", "rat-nang",
                    "itchScore", 99,
                    "khongCoTruongNay", "x"));

            JsonNode problem = create(doctorToken, body, 400);

            assertThat(problem.get("failures")).hasSizeGreaterThanOrEqualTo(3);
        }

        @Test
        void aRecordWithNoClinicalDataYetIsAllowed() throws Exception {
            Map<String, Object> body = encounterBody(null, null);
            body.remove("clinicalData");

            create(doctorToken, body, 201);
        }

        @Test
        void theSchemaIsServedToTheClientSoTheFormIsNotCompiledIn() throws Exception {
            String body = mockMvc.perform(get("/api/encounters/clinical-schema")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            JsonNode served = objectMapper.readTree(body);

            // Publishing a new schema version has to change the screen without a
            // release, which only works if the client renders from this.
            assertThat(served.get("version").asInt()).isEqualTo(1);
            assertThat(served.get("schema").get("properties").has("bodySurfaceAreaPercent"))
                    .isTrue();
        }
    }

    // ---------- consent, access and audit ----------

    @Nested
    class Guards {

        @Test
        void aRecordCannotBeWrittenWithoutConsentToTreatment() throws Exception {
            String freshPatient = createPatient();
            bookAppointment(freshPatient, doctorId, "BOOKED");

            Map<String, Object> body = encounterBody(null, null);
            body.put("patientId", freshPatient);

            // Writing a clinical record is the processing of health data this
            // clinic exists to do. Doing it before the patient has agreed is the
            // case the consent module was built to prevent, so the check belongs
            // at the point of processing rather than only on a screen.
            JsonNode problem = create(doctorToken, body, 403);
            assertThat(problem.get("missingConsent").asText()).isEqualTo("TREATMENT");
        }

        @Test
        void aDoctorWithNoTreatingRelationshipIsRefused() throws Exception {
            ensureStaff("bs-khac", "DOCTOR");
            String otherDoctor = logIn("bs-khac");

            JsonNode created = create(doctorToken, encounterBody(null, null), 201);

            mockMvc.perform(get("/api/encounters/" + created.get("id").asText())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherDoctor))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aReceptionistCannotReachEncountersAtAll() throws Exception {
            ensureStaff("le-tan", "RECEPTIONIST");
            String receptionist = logIn("le-tan");

            // An examination has no administrative half to show them, and the
            // patient access guard returns early for every role except DOCTOR —
            // the trap the allergy endpoints fell into.
            mockMvc.perform(get("/api/encounters?patientId=" + patientId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionist))
                    .andExpect(status().isForbidden());
        }

        @Test
        void anExaminationDatedInTheFutureIsRefused() throws Exception {
            Map<String, Object> body = encounterBody(null, null);
            body.put("encounteredAt", Instant.now().plusSeconds(3600).toString());

            // An examination that has not happened is an appointment. Conflating
            // them would put unperformed work in the medical history.
            create(doctorToken, body, 400);
        }

        @Test
        void creatingARecordIsAudited() throws Exception {
            JsonNode created = create(doctorToken, encounterBody(null, null), 201);

            Map<String, Object> entry = jdbcTemplate.queryForMap("""
                    select action, detail::text as detail from audit_log
                    where entity_type = 'encounter' and entity_id = ?::uuid
                    order by created_at desc limit 1
                    """, created.get("id").asText());

            assertThat(entry.get("action")).isEqualTo("CREATE");
            // The schema version, not the findings. Copying the clinical data
            // into the trail would put a second copy of the medical record in a
            // table with different retention.
            assertThat(entry.get("detail").toString()).contains("schemaVersion");
        }

        @Test
        void theAuditEntryDoesNotCarryTheExaminationFindings() throws Exception {
            JsonNode created = create(doctorToken, encounterBody(null, null), 201);

            Map<String, Object> entry = jdbcTemplate.queryForMap("""
                    select detail::text as detail from audit_log
                    where entity_type = 'encounter' and entity_id = ?::uuid
                    order by created_at desc limit 1
                    """, created.get("id").asText());

            assertThat(entry.get("detail").toString()).doesNotContain("Mảng đỏ");
        }
    }
}
