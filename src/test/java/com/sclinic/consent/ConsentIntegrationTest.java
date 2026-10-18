package com.sclinic.consent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Consent, against a real database.
 *
 * <p>The cases worth testing are the ones where consent looks collected and is
 * not: a record pointing at wording the patient never read, a withdrawal that
 * takes effect at the next restart instead of the next action, and a type with no
 * published wording reported as the patient having refused.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-username=admin",
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-CONSENT-001",
        "sclinic.auth.mfa-required-roles=",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
class ConsentIntegrationTest {

    private static final String PASSWORD = "AdminKham2026!Test";
    private static final String SENSITIVITY_NOTICE =
            "Dữ liệu sức khỏe của bạn là dữ liệu cá nhân nhạy cảm theo Nghị định "
                    + "13/2023/NĐ-CP.";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> EmbeddedPostgresSupport.jdbcUrlFor("consent_it"));
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
    ConsentService consentService;

    @Autowired
    ConsentTypeRepository types;

    @Autowired
    ConsentTypeVersionRepository versions;

    @Autowired
    ConsentRecordRepository recordsRepository;

    private String adminToken;
    private String doctorToken;
    private String receptionistToken;
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
        ensureStaff("le-tan", "RECEPTIONIST");
        doctorToken = logIn("bs-da-lieu");
        receptionistToken = logIn("le-tan");

        // Records first: they reference versions.
        recordsRepository.deleteAllInBatch();
        versions.deleteAllInBatch();

        patientId = createPatient();
        patientUuid = UUID.fromString(patientId);
        bookAppointment(patientId, doctorId);
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
                                "code", "BN-CS-" + UUID.randomUUID().toString().substring(0, 8),
                                "fullName", "Benh Nhan " + UUID.randomUUID().toString()
                                        .substring(0, 8),
                                "phone", "0911222333"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(response).get("id").asText();
    }

    private void bookAppointment(String patient, UUID doctor) {
        jdbcTemplate.update("""
                insert into appointment (id, patient_id, doctor_id, scheduled_at, duration_min, status)
                values (gen_random_uuid(), ?::uuid, ?::uuid, now(), 30, 'BOOKED')
                """, patient, doctor);
    }

    /** Publishes wording for a type, which V14 deliberately ships without. */
    private void publish(ConsentTypeCode type, String content) {
        boolean sensitive = types.findByCode(type.name()).orElseThrow().isSensitive();

        consentService.publishVersion(type, new ConsentVersionRequest(
                content,
                "Phục vụ việc khám và điều trị tại phòng khám",
                sensitive ? SENSITIVITY_NOTICE : null,
                null));
    }

    private JsonNode grant(String token, ConsentTypeCode type, String method,
                           int expectedStatus) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type.name());
        body.put("collectionMethod", method);
        body.put("evidence", "Ban giay da ky, luu tai ho so");

        String response = mockMvc.perform(post("/api/patients/" + patientId + "/consents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return response.isEmpty() ? null : objectMapper.readTree(response);
    }

    private JsonNode withdraw(String token, ConsentTypeCode type, int expectedStatus)
            throws Exception {
        String response = mockMvc.perform(
                        post("/api/patients/" + patientId + "/consents/" + type.name()
                                + "/withdraw")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"note\":\"Benh nhan yeu cau rut\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return response.isEmpty() ? null : objectMapper.readTree(response);
    }

    /*
     * Response bodies are read as UTF-8 explicitly.
     *
     * MockMvc's no-argument getContentAsString falls back to ISO-8859-1 when the
     * response declares no charset, and Spring does not put one on
     * application/json because JSON is UTF-8 by definition. Real HTTP clients
     * therefore read these responses correctly and MockMvc does not, which makes
     * every Vietnamese assertion fail for a reason that has nothing to do with
     * the code under test.
     */
    private JsonNode consentStatus(String token) throws Exception {
        String response = mockMvc.perform(get("/api/patients/" + patientId + "/consents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        return objectMapper.readTree(response);
    }

    private JsonNode statusFor(JsonNode statuses, ConsentTypeCode type) {
        for (JsonNode entry : statuses) {
            if (entry.get("typeCode").asText().equals(type.name())) {
                return entry;
            }
        }
        throw new AssertionError("No status entry for " + type);
    }

    // ---------- requirement 11.1 ----------

    @Nested
    class TheSevenTypes {

        @Test
        void allSevenRequiredTypesAreSeeded() {
            List<String> codes = types.findByActiveTrueOrderByCode().stream()
                    .map(ConsentType::getCode)
                    .toList();

            // Requirement 11.1 fixes this list. Seeded rather than left to
            // configuration because a missing type means a gated action either
            // fails for everyone or passes with nothing there to check.
            assertThat(codes).containsExactlyInAnyOrder(
                    "TREATMENT", "SENSITIVE_DATA", "CLINICAL_PHOTO", "PHOTO_TRAINING",
                    "PHOTO_MARKETING", "APPOINTMENT_REMINDER", "PROCEDURE");
        }

        @Test
        void everyCodeInTheEnumHasARow() {
            // The enum is what calling code names. A value with no row would make
            // requireConsent throw on a type nobody can satisfy.
            for (ConsentTypeCode code : ConsentTypeCode.values()) {
                assertThat(types.findByCode(code.name()))
                        .withFailMessage("No consent_type row for %s", code)
                        .isPresent();
            }
        }

        @Test
        void thePhotographConsentsAreThreeSeparateTypes() {
            // Agreeing that a doctor may look at a photograph of your skin is not
            // agreeing that a lecture hall may, and neither is agreeing to appear
            // in an advertisement. One "photo consent" would collapse all three.
            assertThat(types.findByCode("CLINICAL_PHOTO")).isPresent();
            assertThat(types.findByCode("PHOTO_TRAINING")).isPresent();
            assertThat(types.findByCode("PHOTO_MARKETING")).isPresent();
        }

        @Test
        void marketingConsentDoesNotBlockCareButTreatmentConsentDoes() {
            // Held as data rather than as a rule in code, which is how it stays
            // true: refusing to appear in an advertisement must never affect
            // whether someone is treated.
            assertThat(types.findByCode("PHOTO_MARKETING").orElseThrow().isBlocksCare())
                    .isFalse();
            assertThat(types.findByCode("TREATMENT").orElseThrow().isBlocksCare())
                    .isTrue();
        }

        @Test
        void noWordingIsShippedForAnyType() {
            // The text a patient reads is a legal instrument the clinic answers
            // for. Shipping generated legal prose would mean a clinic going live
            // having collected consent against words nobody there approved.
            assertThat(versions.findAll()).isEmpty();
        }
    }

    // ---------- requirement 11.4 ----------

    @Nested
    class SensitiveDataWording {

        @Test
        void aSensitiveTypeCannotBePublishedWithoutSayingSo() {
            assertThatThrownBy(() -> consentService.publishVersion(
                    ConsentTypeCode.SENSITIVE_DATA,
                    new ConsentVersionRequest("Tôi đồng ý cho phòng khám xử lý dữ liệu.",
                            "Khám và điều trị", null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nhạy cảm");
        }

        @Test
        void blankIsRefusedAsWellAsMissing() {
            assertThatThrownBy(() -> consentService.publishVersion(
                    ConsentTypeCode.SENSITIVE_DATA,
                    new ConsentVersionRequest("Nội dung", "Khám và điều trị", "   ", null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void theDatabaseRefusesItEvenWhenTheServiceIsBypassed() {
            UUID typeId = types.findByCode("SENSITIVE_DATA").orElseThrow().getId();

            // Enforced by trigger because version rows also arrive from
            // migrations and from whatever an administrator runs by hand, and the
            // hand-run case is exactly the one that skips a service check.
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    insert into consent_type_version
                        (id, type_id, version, content, purpose, effective_from, published_at)
                    values (gen_random_uuid(), ?::uuid, 99, 'Noi dung', 'Muc dich',
                            current_date, now())
                    """, typeId.toString()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void aNonSensitiveTypeNeedsNoSuchNotice() {
            // Reminders are not sensitive data, and requiring the notice
            // everywhere would make it boilerplate nobody reads.
            publish(ConsentTypeCode.APPOINTMENT_REMINDER, "Tôi đồng ý nhận tin nhắn nhắc hẹn.");

            assertThat(consentService.currentVersion(ConsentTypeCode.APPOINTMENT_REMINDER)
                    .sensitivityNotice()).isNull();
        }

        @Test
        void theNoticeIsReturnedAsItsOwnFieldForTheUiToPlace() {
            publish(ConsentTypeCode.SENSITIVE_DATA, "Nội dung đồng ý xử lý dữ liệu.");

            // Its own field rather than folded into the content, so the UI can put
            // it where it will be read rather than in paragraph nine.
            assertThat(consentService.currentVersion(ConsentTypeCode.SENSITIVE_DATA)
                    .sensitivityNotice()).isEqualTo(SENSITIVITY_NOTICE);
        }
    }

    // ---------- requirement 11.2, 11.3 ----------

    @Nested
    class WhatARecordHolds {

        @Test
        void aRecordPointsAtTheVersionThePatientRead() throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung bản 1");
            JsonNode first = grant(doctorToken, ConsentTypeCode.TREATMENT, "PAPER", 201);
            assertThat(first.get("version").asInt()).isEqualTo(1);

            publish(ConsentTypeCode.TREATMENT, "Nội dung bản 2, đã sửa");
            JsonNode second = grant(doctorToken, ConsentTypeCode.TREATMENT, "PAPER", 201);

            // The second patient-facing wording is a different document, and a
            // consent that pointed at "the treatment consent form" would point at
            // whatever it says today rather than what was read.
            assertThat(second.get("version").asInt()).isEqualTo(2);
        }

        @Test
        void itStoresPurposeTimeMethodEvidenceAndCollector() throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung");
            JsonNode granted = grant(doctorToken, ConsentTypeCode.TREATMENT,
                    "IN_PERSON_DIGITAL", 201);

            assertThat(granted.get("purpose").asText())
                    .isEqualTo("Phục vụ việc khám và điều trị tại phòng khám");
            assertThat(granted.get("collectionMethod").asText()).isEqualTo("IN_PERSON_DIGITAL");
            assertThat(granted.get("evidence").asText())
                    .isEqualTo("Ban giay da ky, luu tai ho so");
            assertThat(granted.get("grantedAt").isNull()).isFalse();

            UUID id = UUID.fromString(granted.get("id").asText());
            assertThat(recordsRepository.findById(id).orElseThrow().getCollectedBy())
                    .isEqualTo(doctorId);
        }

        @Test
        void thePurposeIsFrozenAtGrantingAndNotReadBackThroughTheVersion()
                throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung bản 1");
            JsonNode granted = grant(doctorToken, ConsentTypeCode.TREATMENT, "PAPER", 201);
            String agreedPurpose = granted.get("purpose").asText();

            // An administrator corrects the wording, including the purpose.
            consentService.publishVersion(ConsentTypeCode.TREATMENT,
                    new ConsentVersionRequest("Nội dung bản 2",
                            "Mục đích đã mở rộng, bao gồm nghiên cứu",
                            SENSITIVITY_NOTICE, null));

            JsonNode history = objectMapper.readTree(
                    mockMvc.perform(get("/api/patients/" + patientId + "/consents/history")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken))
                            .andExpect(status().isOk())
                            .andReturn().getResponse()
                            .getContentAsString(StandardCharsets.UTF_8));

            // The earlier consent must still say what the patient agreed to. If
            // the purpose were read through the join, editing the form would
            // retroactively widen every consent ever given against it.
            assertThat(history.get(0).get("purpose").asText()).isEqualTo(agreedPurpose);
            assertThat(history.get(0).get("purpose").asText()).doesNotContain("nghiên cứu");
        }

        @Test
        void aRecordCannotNameATypeThatDisagreesWithItsVersion() throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung");
            UUID treatmentVersion = versions.findAll().get(0).getId();
            UUID marketingType = types.findByCode("PHOTO_MARKETING").orElseThrow().getId();

            // The type is copied onto the record so the live-consent check is an
            // index lookup rather than a join. A composite foreign key is what
            // keeps the copy honest: without it, a consent to treatment could be
            // filed as consent to advertising.
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    insert into consent_record
                        (id, patient_id, consent_type_id, version_id, purpose, granted_at,
                         collection_method, collected_by)
                    values (gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, 'Muc dich', now(),
                            'PAPER', ?::uuid)
                    """, patientId, marketingType.toString(), treatmentVersion.toString(),
                    doctorId.toString()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void consentCannotBeRecordedAgainstUnpublishedWording() {
            // Nothing is published for this type, and V14 ships none.
            assertThatThrownBy(() -> consentService.grant(patientUuid,
                    new com.sclinic.consent.dto.ConsentGrantRequest(
                            ConsentTypeCode.PROCEDURE, ConsentCollectionMethod.PAPER,
                            null, null),
                    doctorId))
                    .isInstanceOf(ConsentNotConfiguredException.class);
        }

        @Test
        void aFutureEffectiveDateIsNotServedYet() {
            // Staging a planned change must not start collecting consent against
            // wording that does not apply yet.
            consentService.publishVersion(ConsentTypeCode.APPOINTMENT_REMINDER,
                    new ConsentVersionRequest("Nội dung sẽ áp dụng sau", "Nhắc hẹn", null,
                            LocalDate.now().plusDays(30)));

            assertThatThrownBy(() -> consentService.currentVersion(
                    ConsentTypeCode.APPOINTMENT_REMINDER))
                    .isInstanceOf(ConsentNotConfiguredException.class);
        }

        @Test
        void aGrantTimeInTheFutureIsRefused() throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung");

            Map<String, Object> body = new HashMap<>();
            body.put("type", "TREATMENT");
            body.put("collectionMethod", "PAPER");
            body.put("grantedAt", java.time.Instant.now().plusSeconds(86400).toString());

            mockMvc.perform(post("/api/patients/" + patientId + "/consents")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---------- requirement 11.5 ----------

    @Nested
    class RefusingActionsWithoutConsent {

        @Test
        void requireConsentNamesTheMissingType() {
            publish(ConsentTypeCode.PHOTO_MARKETING, "Nội dung quảng cáo");

            assertThatThrownBy(() -> consentService.requireConsent(
                    patientUuid, ConsentTypeCode.PHOTO_MARKETING))
                    .isInstanceOf(ConsentRequiredException.class)
                    // A bare "not permitted" leaves the person at the desk unaware
                    // that the fix is a form the patient can sign in a minute.
                    .hasMessageContaining("PHOTO_MARKETING")
                    .hasMessageContaining("quảng cáo");
        }

        @Test
        void requireConsentPassesOnceConsentIsRecorded() throws Exception {
            publish(ConsentTypeCode.CLINICAL_PHOTO, "Nội dung ảnh lâm sàng");
            grant(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, "PAPER", 201);

            consentService.requireConsent(patientUuid, ConsentTypeCode.CLINICAL_PHOTO);
        }

        @Test
        void consentToOneTypeDoesNotSatisfyAnother() throws Exception {
            publish(ConsentTypeCode.CLINICAL_PHOTO, "Nội dung ảnh lâm sàng");
            publish(ConsentTypeCode.PHOTO_TRAINING, "Nội dung đào tạo");
            grant(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, "PAPER", 201);

            // The reason they are separate types at all.
            assertThatThrownBy(() -> consentService.requireConsent(
                    patientUuid, ConsentTypeCode.PHOTO_TRAINING))
                    .isInstanceOf(ConsentRequiredException.class);
        }

        @Test
        void anUnconfiguredTypeIsReportedAsConfigurationAndNotAsRefusal() {
            // Both leave the action refused and look identical from outside, but
            // one is fixed by a patient signing a form and the other by an
            // administrator publishing wording nobody has written. Reporting the
            // second as the first sends the clinic to collect signatures that
            // will keep failing.
            assertThatThrownBy(() -> consentService.requireConsent(
                    patientUuid, ConsentTypeCode.PROCEDURE))
                    .isInstanceOf(ConsentNotConfiguredException.class)
                    .isNotInstanceOf(ConsentRequiredException.class)
                    .hasMessageContaining("lỗi cấu hình");
        }

        @Test
        void theRefusalCarriesTheMissingTypeForAClientToActOn() throws Exception {
            publish(ConsentTypeCode.PHOTO_MARKETING, "Nội dung quảng cáo");

            // Checked through the exception handler, since the machine-readable
            // field is what lets a client take the user straight to the right form.
            ConsentRequiredException thrown = null;
            try {
                consentService.requireConsent(patientUuid, ConsentTypeCode.PHOTO_MARKETING);
            } catch (ConsentRequiredException e) {
                thrown = e;
            }

            assertThat(thrown).isNotNull();
            assertThat(thrown.getMissing()).isEqualTo(ConsentTypeCode.PHOTO_MARKETING);
        }
    }

    // ---------- requirement 11.6 ----------

    @Nested
    class Withdrawal {

        @Test
        void withdrawalRecordsTheTimeAndTakesEffectImmediately() throws Exception {
            publish(ConsentTypeCode.CLINICAL_PHOTO, "Nội dung ảnh lâm sàng");
            grant(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, "PAPER", 201);
            consentService.requireConsent(patientUuid, ConsentTypeCode.CLINICAL_PHOTO);

            JsonNode withdrawn = withdraw(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, 200);
            assertThat(withdrawn.get("withdrawnAt").isNull()).isFalse();
            assertThat(withdrawn.get("live").asBoolean()).isFalse();

            // The next action, not the next restart. requireConsent reads the
            // record rather than a cached conclusion, which is the only way a
            // withdrawal can bite at once.
            assertThatThrownBy(() -> consentService.requireConsent(
                    patientUuid, ConsentTypeCode.CLINICAL_PHOTO))
                    .isInstanceOf(ConsentRequiredException.class);
        }

        @Test
        void theWithdrawnRecordIsKeptAsEvidenceThatConsentOnceExisted()
                throws Exception {
            publish(ConsentTypeCode.CLINICAL_PHOTO, "Nội dung");
            JsonNode granted = grant(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, "PAPER", 201);
            withdraw(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, 200);

            // Photographs taken while consent was live were taken lawfully, and
            // the record of that is the only thing which shows it.
            assertThat(recordsRepository.findById(
                    UUID.fromString(granted.get("id").asText()))).isPresent();
        }

        @Test
        void noReasonIsRequiredToWithdraw() throws Exception {
            publish(ConsentTypeCode.CLINICAL_PHOTO, "Nội dung");
            grant(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, "PAPER", 201);

            // The right to withdraw is unconditional. A mandatory justification
            // field would make the system an obstacle to a right it exists to
            // serve, so an empty body is a valid withdrawal.
            mockMvc.perform(post("/api/patients/" + patientId + "/consents/CLINICAL_PHOTO"
                            + "/withdraw")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken))
                    .andExpect(status().isOk());
        }

        @Test
        void aReceptionistCanRecordAWithdrawal() throws Exception {
            publish(ConsentTypeCode.APPOINTMENT_REMINDER, "Nội dung nhắc hẹn");
            grant(receptionistToken, ConsentTypeCode.APPOINTMENT_REMINDER, "VERBAL", 201);

            // Whoever the patient speaks to has to be able to record it. Refusing
            // because the wrong role is at the desk does not make the patient come
            // back later; it makes the withdrawal go unrecorded.
            withdraw(receptionistToken, ConsentTypeCode.APPOINTMENT_REMINDER, 200);

            assertThat(consentService.mayReceiveReminders(patientUuid)).isFalse();
        }

        @Test
        void withdrawingWhenNothingIsLiveIsNotFound() throws Exception {
            publish(ConsentTypeCode.CLINICAL_PHOTO, "Nội dung");

            withdraw(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, 404);
        }

        @Test
        void withdrawingTwiceIsRefusedRatherThanOverwritingTheFirstTime()
                throws Exception {
            publish(ConsentTypeCode.CLINICAL_PHOTO, "Nội dung");
            grant(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, "PAPER", 201);
            withdraw(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, 200);

            // Otherwise the second call moves the withdrawal timestamp, losing
            // when the patient actually withdrew — which is the one fact
            // requirement 11.6 asks to be recorded.
            withdraw(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, 404);
        }

        @Test
        void consentCanBeGivenAgainAfterBeingWithdrawn() throws Exception {
            publish(ConsentTypeCode.CLINICAL_PHOTO, "Nội dung");
            grant(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, "PAPER", 201);
            withdraw(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, 200);

            // The partial unique index is partial for this: a withdrawn consent
            // must not stop the patient changing their mind back.
            grant(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, "PAPER", 201);
            consentService.requireConsent(patientUuid, ConsentTypeCode.CLINICAL_PHOTO);
        }
    }

    // ---------- re-consenting ----------

    @Nested
    class Superseding {

        @Test
        void consentingAgainSupersedesRatherThanWithdraws() throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung bản 1");
            JsonNode first = grant(doctorToken, ConsentTypeCode.TREATMENT, "PAPER", 201);

            publish(ConsentTypeCode.TREATMENT, "Nội dung bản 2");
            grant(doctorToken, ConsentTypeCode.TREATMENT, "PAPER", 201);

            ConsentRecord old = recordsRepository.findById(
                    UUID.fromString(first.get("id").asText())).orElseThrow();

            // The patient agreed again; they did not withdraw. Recording it as a
            // withdrawal would put an event in the trail that never happened.
            assertThat(old.getSupersededAt()).isNotNull();
            assertThat(old.getWithdrawnAt()).isNull();
            assertThat(old.getSupersededBy()).isNotNull();
        }

        @Test
        void exactlyOneRecordIsLiveAfterReConsenting() throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung bản 1");
            grant(doctorToken, ConsentTypeCode.TREATMENT, "PAPER", 201);
            publish(ConsentTypeCode.TREATMENT, "Nội dung bản 2");
            grant(doctorToken, ConsentTypeCode.TREATMENT, "PAPER", 201);

            // Two live records would make "which version is in force" unanswerable,
            // which defeats the point of versioning the wording at all.
            long live = recordsRepository.findByPatientIdOrderByGrantedAtDesc(patientUuid)
                    .stream()
                    .filter(ConsentRecord::isLive)
                    .count();

            assertThat(live).isEqualTo(1);
        }

        @Test
        void theDatabaseRefusesASecondLiveRecordForTheSameType() throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung");
            grant(doctorToken, ConsentTypeCode.TREATMENT, "PAPER", 201);

            UUID typeId = types.findByCode("TREATMENT").orElseThrow().getId();
            UUID versionId = versions.findAll().get(0).getId();

            assertThatThrownBy(() -> jdbcTemplate.update("""
                    insert into consent_record
                        (id, patient_id, consent_type_id, version_id, purpose, granted_at,
                         collection_method, collected_by)
                    values (gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, 'Muc dich', now(),
                            'PAPER', ?::uuid)
                    """, patientId, typeId.toString(), versionId.toString(),
                    doctorId.toString()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void theOlderWordingStaysAsProofOfWhatAppliedUntilThen() throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung bản 1");
            grant(doctorToken, ConsentTypeCode.TREATMENT, "PAPER", 201);
            publish(ConsentTypeCode.TREATMENT, "Nội dung bản 2");
            grant(doctorToken, ConsentTypeCode.TREATMENT, "PAPER", 201);

            List<ConsentRecord> history =
                    recordsRepository.findByPatientIdOrderByGrantedAtDesc(patientUuid);

            assertThat(history).hasSize(2);
        }
    }

    // ---------- requirement 11.7 ----------

    @Nested
    class AppointmentReminders {

        @Test
        void remindersAreNotSentWithoutConsent() {
            publish(ConsentTypeCode.APPOINTMENT_REMINDER, "Nội dung nhắc hẹn");

            // Opt-in, not opt-out.
            assertThat(consentService.mayReceiveReminders(patientUuid)).isFalse();
        }

        @Test
        void remindersAreAllowedOnceConsentIsGiven() throws Exception {
            publish(ConsentTypeCode.APPOINTMENT_REMINDER, "Nội dung nhắc hẹn");
            grant(receptionistToken, ConsentTypeCode.APPOINTMENT_REMINDER, "VERBAL", 201);

            assertThat(consentService.mayReceiveReminders(patientUuid)).isTrue();
        }

        @Test
        void withdrawingReminderConsentStopsThemImmediately() throws Exception {
            publish(ConsentTypeCode.APPOINTMENT_REMINDER, "Nội dung nhắc hẹn");
            grant(receptionistToken, ConsentTypeCode.APPOINTMENT_REMINDER, "VERBAL", 201);
            assertThat(consentService.mayReceiveReminders(patientUuid)).isTrue();

            withdraw(receptionistToken, ConsentTypeCode.APPOINTMENT_REMINDER, 200);

            // There is no sender yet. This is the question it has to ask, per
            // send: building a recipient list once and sending from it later is
            // how a withdrawal arrives too late to matter.
            assertThat(consentService.mayReceiveReminders(patientUuid)).isFalse();
        }

        @Test
        void reminderConsentIsIndependentOfTreatmentConsent() throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung điều trị");
            publish(ConsentTypeCode.APPOINTMENT_REMINDER, "Nội dung nhắc hẹn");
            grant(doctorToken, ConsentTypeCode.TREATMENT, "PAPER", 201);

            // Agreeing to be treated is not agreeing to be messaged.
            assertThat(consentService.mayReceiveReminders(patientUuid)).isFalse();
        }
    }

    // ---------- what the desk sees ----------

    @Nested
    class StatusList {

        @Test
        void everyTypeIsListedIncludingTheOnesWithNothingRecorded() throws Exception {
            JsonNode statuses = consentStatus(receptionistToken);

            // A list of what has been collected cannot show what is missing, and
            // what is missing is what the person at the desk needs to know.
            assertThat(statuses).hasSize(7);
            assertThat(statusFor(statuses, ConsentTypeCode.TREATMENT).get("granted")
                    .asBoolean()).isFalse();
        }

        @Test
        void anUnconfiguredTypeIsMarkedAsSuchRatherThanAsUngranted() throws Exception {
            JsonNode statuses = consentStatus(adminToken);
            JsonNode procedure = statusFor(statuses, ConsentTypeCode.PROCEDURE);

            // Otherwise the desk chases a signature for something that cannot be
            // recorded until an administrator publishes the wording.
            assertThat(procedure.get("notConfigured").asBoolean()).isTrue();
            assertThat(procedure.get("currentVersion").isNull()).isTrue();
        }

        @Test
        void aWithdrawnConsentReadsAsWithdrawnAndNotAsNeverGiven() throws Exception {
            publish(ConsentTypeCode.CLINICAL_PHOTO, "Nội dung");
            grant(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, "PAPER", 201);
            withdraw(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, 200);

            JsonNode photo = statusFor(consentStatus(doctorToken), ConsentTypeCode.CLINICAL_PHOTO);

            assertThat(photo.get("granted").asBoolean()).isFalse();
            assertThat(photo.get("withdrawnAt").isNull()).isFalse();
        }

        @Test
        void consentAgainstOlderWordingIsFlaggedButStillCounts() throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung bản 1");
            grant(doctorToken, ConsentTypeCode.TREATMENT, "PAPER", 201);
            publish(ConsentTypeCode.TREATMENT, "Nội dung bản 2");

            JsonNode treatment = statusFor(consentStatus(doctorToken), ConsentTypeCode.TREATMENT);

            // Still consent: revoking it because the clinic edited a form would be
            // wrong. Flagged, because fresh consent against current wording is
            // usually what a compliance review wants to see.
            assertThat(treatment.get("granted").asBoolean()).isTrue();
            assertThat(treatment.get("supersededWording").asBoolean()).isTrue();
            consentService.requireConsent(patientUuid, ConsentTypeCode.TREATMENT);
        }

        @Test
        void sensitiveTypesAreMarkedSoTheUiCanSayWhyItIsAsking() throws Exception {
            JsonNode statuses = consentStatus(receptionistToken);

            assertThat(statusFor(statuses, ConsentTypeCode.SENSITIVE_DATA).get("sensitive")
                    .asBoolean()).isTrue();
            assertThat(statusFor(statuses, ConsentTypeCode.APPOINTMENT_REMINDER)
                    .get("sensitive").asBoolean()).isFalse();
        }
    }

    // ---------- access and audit ----------

    @Nested
    class AccessAndAudit {

        @Test
        void aReceptionistCanCollectConsent() throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung");

            // Consent is collected at reception. That is the job.
            grant(receptionistToken, ConsentTypeCode.TREATMENT, "PAPER", 201);
        }

        @Test
        void onlyAnAdminMayPublishWording() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "content", "Nội dung mới",
                    "purpose", "Khám và điều trị",
                    "sensitivityNotice", SENSITIVITY_NOTICE));

            // Deciding what patients are asked is a different job, with a
            // different risk, from recording what they answered.
            mockMvc.perform(post("/api/consent-types/TREATMENT/versions")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/api/consent-types/TREATMENT/versions")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        @Test
        void anUnauthenticatedCallerIsRefused() throws Exception {
            mockMvc.perform(get("/api/patients/" + patientId + "/consents"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void grantingAndWithdrawingAreBothAudited() throws Exception {
            publish(ConsentTypeCode.CLINICAL_PHOTO, "Nội dung");
            JsonNode granted = grant(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, "PAPER", 201);
            String id = granted.get("id").asText();
            withdraw(doctorToken, ConsentTypeCode.CLINICAL_PHOTO, 200);

            List<Map<String, Object>> entries = jdbcTemplate.queryForList("""
                    select action, detail::text as detail from audit_log
                    where entity_type = 'consent_record' and entity_id = ?::uuid
                    order by created_at
                    """, id);

            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).get("action")).isEqualTo("CREATE");
            // The type and the version are codes from fixed sets, not free text
            // about the patient, and which consent was taken is the point of
            // auditing this.
            assertThat(entries.get(0).get("detail").toString())
                    .contains("CLINICAL_PHOTO");
            assertThat(entries.get(1).get("action")).isEqualTo("UPDATE");
        }

        @Test
        void publishingWordingIsAudited() throws Exception {
            publish(ConsentTypeCode.TREATMENT, "Nội dung");

            Integer published = jdbcTemplate.queryForObject("""
                    select count(*) from audit_log
                    where entity_type = 'consent_type_version' and action = 'CREATE'
                    """, Integer.class);

            // Changing what patients are asked to agree to is a change worth
            // being able to date afterwards.
            assertThat(published).isGreaterThanOrEqualTo(1);
        }
    }
}
