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
import com.sclinic.signing.DigitalSignatureProvider;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import com.sclinic.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
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
 * Signing a record, and the fact that nothing can then change it.
 *
 * <p>Requirements 14.5 to 14.9. The tests that matter here are the ones that try
 * to edit a signed record by every route the application offers and then by going
 * round it entirely with SQL, because requirement 14.6 asks for the lock at both
 * levels and only the second one survives a migration written in a hurry.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-username=admin",
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-SIGN-001",
        "sclinic.auth.mfa-required-roles=",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
@Import(EncounterSigningIntegrationTest.TestSigningConfig.class)
class EncounterSigningIntegrationTest {

    private static final String PASSWORD = "AdminKham2026!Test";

    /**
     * Supplies a provider that really signs, so the verification path is the real
     * one. {@code @Primary} rather than replacing the bean, because the
     * production default deliberately refuses to sign and that default is itself
     * worth keeping in the context.
     */
    @TestConfiguration
    static class TestSigningConfig {

        @Bean
        @Primary
        DigitalSignatureProvider testSignatureProvider() {
            return new TestSignatureProvider();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> EmbeddedPostgresSupport.jdbcUrlFor("encounter_sign_it"));
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
    DigitalSignatureProvider signatureProvider;

    private String adminToken;
    private String doctorToken;
    private UUID doctorId;
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

        doctorId = ensureSigningDoctor();
        doctorToken = logIn("bs-ky-so");

        jdbcTemplate.execute("alter table encounter_diagnosis disable trigger all");
        jdbcTemplate.execute("alter table encounter disable trigger all");
        jdbcTemplate.update("delete from encounter_diagnosis");
        jdbcTemplate.update("delete from encounter");
        jdbcTemplate.execute("alter table encounter enable trigger all");
        jdbcTemplate.execute("alter table encounter_diagnosis enable trigger all");
        jdbcTemplate.execute("alter table signature disable trigger all");
        jdbcTemplate.update("delete from signature");
        jdbcTemplate.execute("alter table signature enable trigger all");

        icd10Repository.deleteAllInBatch();
        seedIcd10("L20.9", "Viêm da cơ địa");
        seedIcd10("L40.0", "Vảy nến thể mảng");

        publishConsentWording();
        patientId = createPatient();
        // The treating relationship the access rule derives from. Without it the
        // doctor is refused the record before any of this is reached.
        bookAppointment(patientId, doctorId);
        grantConsent(UUID.fromString(patientId));
    }

    private void bookAppointment(String patient, UUID doctor) {
        jdbcTemplate.update("""
                insert into appointment (id, patient_id, doctor_id, scheduled_at, duration_min, status)
                values (gen_random_uuid(), ?::uuid, ?::uuid, now(), 30, 'BOOKED')
                """, patient, doctor.toString());
    }

    // ---------- fixtures ----------

    /** A doctor with the licence and credential that signing requires. */
    private UUID ensureSigningDoctor() {
        Staff doctor = staffRepository.findByUsernameAndActiveTrue("bs-ky-so")
                .orElseGet(() -> {
                    Staff fresh = new Staff();
                    fresh.setUsername("bs-ky-so");
                    fresh.setFullName("BS Ky So");
                    fresh.setRole("DOCTOR");
                    fresh.setActive(true);
                    return fresh;
                });

        doctor.setPasswordHash(passwordEncoder.encode(PASSWORD));
        doctor.setMustChangePassword(false);
        doctor.setFailedAttempts(0);
        doctor.setLockedUntil(null);
        doctor.setPracticeLicenceNo("GPHN-12345");
        doctor.setSigningCredentialId("cred-test-1");
        doctor.setSigningProvider("TEST");
        return staffRepository.save(doctor).getId();
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

    private void publishConsentWording() {
        for (ConsentTypeCode type : List.of(ConsentTypeCode.TREATMENT,
                ConsentTypeCode.SENSITIVE_DATA)) {
            boolean sensitive = consentTypes.findByCode(type.name()).orElseThrow().isSensitive();
            try {
                consentService.currentVersion(type);
            } catch (RuntimeException notPublishedYet) {
                consentService.publishVersion(type, new ConsentVersionRequest(
                        "Nội dung đồng ý dùng cho kiểm thử",
                        "Phục vụ khám và điều trị",
                        sensitive ? "Dữ liệu sức khỏe là dữ liệu nhạy cảm." : null,
                        null));
            }
        }
    }

    private void grantConsent(UUID patient) {
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
                                "code", "BN-SG-" + UUID.randomUUID().toString().substring(0, 8),
                                "fullName", "Benh Nhan Ky So",
                                "phone", "0911222333"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(response).get("id").asText();
    }

    /** A draft with one primary diagnosis, ready to sign. */
    private String draft() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("reason", "Ngứa, mẩn đỏ");
        body.put("clinicalData", Map.of("severity", "MODERATE", "itchScore", 6));
        body.put("diagnoses", List.of(Map.of("icd10Code", "L20.9", "primary", true)));

        String response = mockMvc.perform(post("/api/encounters")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(response).get("id").asText();
    }

    private JsonNode sign(String encounterId, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post("/api/encounters/" + encounterId + "/sign")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return response.isEmpty() ? null : objectMapper.readTree(response);
    }

    private String signedDraft() throws Exception {
        String id = draft();
        sign(id, 200);
        return id;
    }

    private JsonNode signatureStatus(String encounterId) throws Exception {
        String response = mockMvc.perform(
                        get("/api/encounters/" + encounterId + "/signature")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(response);
    }

    // ---------- requirement 14.5 ----------

    @Nested
    class Signing {

        @Test
        void signingADraftLocksIt() throws Exception {
            JsonNode signed = sign(draft(), 200);

            assertThat(signed.get("status").asText()).isEqualTo("SIGNED");
            assertThat(signed.get("editable").asBoolean()).isFalse();
        }

        @Test
        void theSignatureIsStoredWithEnoughToVerifyItLater() throws Exception {
            String id = signedDraft();

            Map<String, Object> signature = jdbcTemplate.queryForMap("""
                    select signed_hash, hash_algorithm, signature_algorithm,
                           certificate_chain, provider_code, signer_staff_id
                    from signature where entity_type = 'encounter' and entity_id = ?::uuid
                    """, id);

            // All of it, because verification in ten years has only the row.
            assertThat(signature.get("signed_hash")).isNotNull();
            assertThat(signature.get("hash_algorithm")).isEqualTo("SHA-256");
            assertThat(signature.get("signature_algorithm")).isNotNull();
            assertThat(signature.get("certificate_chain")).isNotNull();
            assertThat(signature.get("provider_code")).isEqualTo("TEST");
            assertThat(signature.get("signer_staff_id")).isNotNull();
        }

        @Test
        void aRecordWithNoPrimaryDiagnosisCannotBeSigned() throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("patientId", patientId);
            body.put("diagnoses", List.of(Map.of("icd10Code", "L20.9", "primary", false)));

            String id = objectMapper.readTree(mockMvc.perform(post("/api/encounters")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .get("id").asText();

            // Requirement 13.2's "exactly one" is enforced at the moment the
            // record stops being a draft, which is also the moment it becomes a
            // legal document — not on every insert, which would make the first
            // diagnosis typed automatically the primary one.
            sign(id, 400);
        }

        @Test
        void aDoctorWithNoCertificateCannotSign() throws Exception {
            Staff doctor = staffRepository.findById(doctorId).orElseThrow();
            doctor.setSigningCredentialId(null);
            staffRepository.save(doctor);

            sign(draft(), 422);
        }

        @Test
        void aDoctorWithNoPracticeLicenceCannotSign() throws Exception {
            Staff doctor = staffRepository.findById(doctorId).orElseThrow();
            doctor.setPracticeLicenceNo(null);
            staffRepository.save(doctor);

            // Requirement 14.3: "signed by Dr X" means little without the licence
            // that permitted Dr X to sign.
            sign(draft(), 422);
        }

        @Test
        void aRefusedSignatureLeavesTheRecordADraftAndWritesNoSignature()
                throws Exception {
            String id = draft();
            ((TestSignatureProvider) signatureProvider).refuseNextSignature(true);

            try {
                sign(id, 422);
            } finally {
                ((TestSignatureProvider) signatureProvider).refuseNextSignature(false);
            }

            // The state that must never exist is a record marked SIGNED with no
            // signature behind it. One transaction, so there is no window.
            assertThat(jdbcTemplate.queryForObject(
                    "select status from encounter where id = ?::uuid", String.class, id))
                    .isEqualTo("DRAFT");
            assertThat(jdbcTemplate.queryForObject("""
                    select count(*) from signature
                    where entity_type = 'encounter' and entity_id = ?::uuid
                    """, Long.class, id)).isZero();
        }

        @Test
        void signingTwiceIsRefused() throws Exception {
            String id = signedDraft();

            sign(id, 409);
        }

        @Test
        void signingIsAuditedAsItsOwnAction() throws Exception {
            String id = signedDraft();

            Long signEntries = jdbcTemplate.queryForObject("""
                    select count(*) from audit_log
                    where entity_type = 'encounter' and entity_id = ?::uuid and action = 'SIGN'
                    """, Long.class, id);

            // Not an UPDATE: this is the moment a draft became a legal document,
            // and a review should find it in one query.
            assertThat(signEntries).isEqualTo(1);
        }
    }

    // ---------- requirement 14.6 ----------

    @Nested
    class SignedRecordsCannotBeEdited {

        @Test
        void theUpdateEndpointRefusesASignedRecord() throws Exception {
            String id = signedDraft();

            Map<String, Object> update = new HashMap<>();
            update.put("reason", "Sửa lại sau khi đã ký");

            mockMvc.perform(put("/api/encounters/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(update)))
                    .andExpect(status().isConflict());
        }

        @Test
        void theRefusalPointsAtTheAmendmentPath() throws Exception {
            String id = signedDraft();

            String body = mockMvc.perform(put("/api/encounters/" + id)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"x\"}"))
                    .andExpect(status().isConflict())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            // Otherwise a clinician concludes the system lost their correction.
            assertThat(body).contains("đính chính");
        }

        @Test
        void theDatabaseRefusesAContentChangeThatBypassesTheService()
                throws Exception {
            String id = signedDraft();

            // The level that matters. A service check protects callers that go
            // through the service; a medical record has to survive the ones that
            // do not - a migration, a fix-up script, a second application written
            // against this database years from now.
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "update encounter set reason = 'sửa trực tiếp' where id = ?::uuid", id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void theDatabaseRefusesAClinicalDataChange() throws Exception {
            String id = signedDraft();

            assertThatThrownBy(() -> jdbcTemplate.update("""
                    update encounter set clinical_data = '{"severity":"MILD"}'::jsonb
                    where id = ?::uuid
                    """, id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void theDatabaseRefusesEditingTheDiagnosesOfASignedRecord() throws Exception {
            String id = signedDraft();

            // The diagnoses live in their own table, so locking the encounter
            // alone would leave the most consequential part of the record open -
            // and the diagnosis is exactly what somebody would want to change
            // after the fact.
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    update encounter_diagnosis set icd10_code = 'L40.0'
                    where encounter_id = ?::uuid
                    """, id))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "delete from encounter_diagnosis where encounter_id = ?::uuid", id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void theDatabaseRefusesDeletingASignedRecord() throws Exception {
            String id = signedDraft();

            // Destruction is a retention decision expressed as a soft delete plus
            // a date, not a DELETE statement. A signed row removed from the table
            // takes its signature's subject with it.
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "delete from encounter where id = ?::uuid", id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void theDatabaseRefusesAnArbitraryStatusChange() throws Exception {
            String id = signedDraft();

            // Back to DRAFT would be the simplest way to make a signed record
            // editable again, so it is the first thing to close.
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "update encounter set status = 'DRAFT' where id = ?::uuid", id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void retiringASignedRecordIsStillPossible() throws Exception {
            String id = signedDraft();

            // Immutability is about clinical content, not about the record being
            // impossible to retire. Freezing the lifecycle columns too would mean
            // a signed record could never be archived, which is not what
            // requirement 14.6 asks for.
            jdbcTemplate.update("""
                    update encounter
                    set deleted_at = now(), deleted_by = ?::uuid, delete_reason = 'Trùng bản ghi'
                    where id = ?::uuid
                    """, doctorId.toString(), id);

            assertThat(jdbcTemplate.queryForObject(
                    "select delete_reason from encounter where id = ?::uuid", String.class, id))
                    .isEqualTo("Trùng bản ghi");
        }

        @Test
        void aSignatureRowCannotBeChangedOrRemoved() throws Exception {
            String id = signedDraft();

            // A signature that can be replaced is not evidence of anything.
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    update signature set signature_value = 'forged'
                    where entity_type = 'encounter' and entity_id = ?::uuid
                    """, id))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThatThrownBy(() -> jdbcTemplate.update("""
                    delete from signature
                    where entity_type = 'encounter' and entity_id = ?::uuid
                    """, id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ---------- requirement 14.7, 14.8 ----------

    @Nested
    class Amendments {

        private JsonNode openAmendment(String originalId, String reason, int expectedStatus)
                throws Exception {
            String response = mockMvc.perform(
                            post("/api/encounters/" + originalId + "/amendments")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"reason\":\"" + reason + "\"}"))
                    .andExpect(status().is(expectedStatus))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            return response.isEmpty() ? null : objectMapper.readTree(response);
        }

        @Test
        void anAmendmentIsANewDraftPointingAtTheOriginal() throws Exception {
            String original = signedDraft();

            JsonNode amendment = openAmendment(original, "Ghi sai mức độ", 201);

            assertThat(amendment.get("status").asText()).isEqualTo("DRAFT");
            assertThat(amendment.get("amendsEncounterId").asText()).isEqualTo(original);
            assertThat(amendment.get("amendmentReason").asText()).isEqualTo("Ghi sai mức độ");
        }

        @Test
        void aReasonIsRequired() throws Exception {
            String original = signedDraft();

            // A correction to a signed medical record without a stated reason
            // cannot be reviewed afterwards.
            openAmendment(original, "", 400);
        }

        @Test
        void theOriginalStaysInForceUntilTheAmendmentIsSigned() throws Exception {
            String original = signedDraft();
            openAmendment(original, "Ghi sai mức độ", 201);

            // Requirement 14.7: the amendment takes effect on being signed, not
            // on being written. Until then the original is still the record.
            assertThat(jdbcTemplate.queryForObject(
                    "select status from encounter where id = ?::uuid", String.class, original))
                    .isEqualTo("SIGNED");
        }

        @Test
        void signingTheAmendmentMarksTheOriginalAmended() throws Exception {
            String original = signedDraft();
            String amendment = openAmendment(original, "Ghi sai mức độ", 201)
                    .get("id").asText();

            sign(amendment, 200);

            assertThat(jdbcTemplate.queryForObject(
                    "select status from encounter where id = ?::uuid", String.class, original))
                    .isEqualTo("AMENDED");
        }

        @Test
        void theOriginalsSignatureStillVerifiesAfterItIsMarkedAmended()
                throws Exception {
            String original = signedDraft();
            String amendment = openAmendment(original, "Ghi sai mức độ", 201)
                    .get("id").asText();
            sign(amendment, 200);

            JsonNode status = signatureStatus(original);

            // The reason status is excluded from the canonical form. If it were
            // hashed, correcting a record properly would break the original's own
            // signature and the record would look tampered with by the act of
            // correcting it.
            assertThat(status.get("valid").asBoolean()).isTrue();
            assertThat(status.get("dataUnchanged").asBoolean()).isTrue();
        }

        @Test
        void anAmendmentStartsAsACopySoNothingChangesByAccident() throws Exception {
            String original = signedDraft();

            JsonNode amendment = openAmendment(original, "Sửa chẩn đoán", 201);

            // A correction is usually a small change to a long record. Making the
            // doctor retype the examination would mean the amendment differed
            // from the original in ways nobody chose.
            assertThat(amendment.get("reason").asText()).isEqualTo("Ngứa, mẩn đỏ");
            assertThat(amendment.get("diagnoses")).hasSize(1);
            assertThat(amendment.get("clinicalData").get("severity").asText())
                    .isEqualTo("MODERATE");
        }

        @Test
        void anAmendmentIsEditableBeforeItIsSigned() throws Exception {
            String original = signedDraft();
            String amendment = openAmendment(original, "Sửa chẩn đoán", 201)
                    .get("id").asText();

            Map<String, Object> update = new HashMap<>();
            update.put("reason", "Ngứa, mẩn đỏ, đã sửa");
            update.put("diagnoses", List.of(Map.of("icd10Code", "L40.0", "primary", true)));

            mockMvc.perform(put("/api/encounters/" + amendment)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(update)))
                    .andExpect(status().isOk());
        }

        @Test
        void aDraftCannotBeAmendedBecauseItCanSimplyBeEdited() throws Exception {
            String draft = draft();

            // Sending someone down the amendment path for a draft would leave two
            // records where there should be one.
            openAmendment(draft, "Không cần thiết", 400);
        }

        @Test
        void theChainReadsInOrderFromTheOriginal() throws Exception {
            String original = signedDraft();
            String first = openAmendment(original, "Lần sửa thứ nhất", 201)
                    .get("id").asText();
            sign(first, 200);
            String second = openAmendment(first, "Lần sửa thứ hai", 201)
                    .get("id").asText();
            sign(second, 200);

            String body = mockMvc.perform(get("/api/encounters/" + original + "/chain")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            JsonNode chain = objectMapper.readTree(body);

            assertThat(chain).hasSize(3);
            assertThat(chain.get(0).get("id").asText()).isEqualTo(original);
            assertThat(chain.get(1).get("id").asText()).isEqualTo(first);
            assertThat(chain.get(2).get("id").asText()).isEqualTo(second);
        }

        @Test
        void theChainReadsTheSameFromAnyLinkInIt() throws Exception {
            String original = signedDraft();
            String amendment = openAmendment(original, "Sửa", 201).get("id").asText();
            sign(amendment, 200);

            String fromMiddle = mockMvc.perform(
                            get("/api/encounters/" + amendment + "/chain")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            // A history that read differently depending on which link you opened
            // would be worse than no history.
            assertThat(objectMapper.readTree(fromMiddle).get(0).get("id").asText())
                    .isEqualTo(original);
        }
    }

    // ---------- requirement 14.9 ----------

    @Nested
    class Verification {

        @Test
        void aFreshlySignedRecordVerifies() throws Exception {
            JsonNode status = signatureStatus(signedDraft());

            assertThat(status.get("signed").asBoolean()).isTrue();
            assertThat(status.get("valid").asBoolean()).isTrue();
        }

        @Test
        void anUnsignedDraftIsReportedAsUnsignedRatherThanInvalid() throws Exception {
            JsonNode status = signatureStatus(draft());

            // Otherwise every draft in the clinic would display a warning.
            assertThat(status.get("signed").asBoolean()).isFalse();
            assertThat(status.get("problem").isNull()).isTrue();
        }

        @Test
        void aRecordAlteredBehindTheTriggersFailsVerificationAsAlteredData()
                throws Exception {
            String id = signedDraft();

            // Only reachable by turning the trigger off, which is the point: this
            // is what verification is for, and it is the backstop behind the lock
            // rather than a substitute for it.
            jdbcTemplate.execute("alter table encounter disable trigger all");
            jdbcTemplate.update(
                    "update encounter set reason = 'đã bị sửa' where id = ?::uuid", id);
            jdbcTemplate.execute("alter table encounter enable trigger all");

            JsonNode status = signatureStatus(id);

            assertThat(status.get("valid").asBoolean()).isFalse();
            assertThat(status.get("dataUnchanged").asBoolean()).isFalse();
            // And not reported as a broken signature: the signature is fine, the
            // record is not, and only one of those is an incident.
            assertThat(status.get("signatureSound").asBoolean()).isTrue();
        }

        @Test
        void anAlteredDiagnosisAlsoFailsVerification() throws Exception {
            String id = signedDraft();

            jdbcTemplate.execute("alter table encounter_diagnosis disable trigger all");
            jdbcTemplate.update("""
                    update encounter_diagnosis set icd10_code = 'L40.0', icd10_name = 'Vảy nến'
                    where encounter_id = ?::uuid
                    """, id);
            jdbcTemplate.execute("alter table encounter_diagnosis enable trigger all");

            // The diagnoses are inside the hash, so changing one breaks it. This
            // is why the canonical form covers them rather than only the encounter
            // row.
            assertThat(signatureStatus(id).get("dataUnchanged").asBoolean()).isFalse();
        }

        @Test
        void verificationDoesNotDependOnTheProviderBeingReachable() throws Exception {
            String id = signedDraft();

            // Nothing in this test can reach a network, and it still verifies.
            // A record signed five years ago has to stay verifiable after the
            // clinic changes provider or the provider stops answering.
            assertThat(signatureStatus(id).get("valid").asBoolean()).isTrue();
        }
    }
}
