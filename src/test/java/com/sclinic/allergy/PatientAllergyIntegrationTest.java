package com.sclinic.allergy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclinic.catalogue.CatalogAllergen;
import com.sclinic.catalogue.CatalogAllergenRepository;
import com.sclinic.catalogue.CatalogDrugRepository;
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
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Structured allergies, against a real database.
 *
 * <p>The thing being tested is not that a row can be saved. It is that the two
 * ways an allergy silently stops working are closed: a duplicate record that
 * lets a severity be downgraded depending on which row is read first, and a
 * free-text sentence that looks like recorded information while being invisible
 * to any check.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-username=admin",
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-ALG-001",
        "sclinic.auth.mfa-required-roles=",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
class PatientAllergyIntegrationTest {

    private static final String PASSWORD = "AdminKham2026!Test";
    private static final String PENICILLIN = "ALG-PENI";
    private static final String SULFA = "ALG-SULFA";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> EmbeddedPostgresSupport.jdbcUrlFor("allergy_it"));
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
    CatalogAllergenRepository allergenRepository;

    @Autowired
    CatalogDrugRepository drugRepository;

    @Autowired
    PatientAllergyRepository allergyRepository;

    @Autowired
    PatientAllergyService allergyService;

    private String adminToken;
    private String doctorToken;
    private String otherDoctorToken;
    private String receptionistToken;
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

        doctorId = ensureStaff("bs-da-lieu", "DOCTOR");
        ensureStaff("bs-khac", "DOCTOR");
        ensureStaff("le-tan", "RECEPTIONIST");
        doctorToken = logIn("bs-da-lieu");
        otherDoctorToken = logIn("bs-khac");
        receptionistToken = logIn("le-tan");

        allergyRepository.deleteAllInBatch();
        // Drugs reference allergens through the join table, so they go first.
        drugRepository.deleteAllInBatch();
        allergenRepository.deleteAllInBatch();
        seedAllergen(PENICILLIN, "Penicillin", "DRUG");
        seedAllergen(SULFA, "Sulfamid", "DRUG");

        patientId = createPatient(null);
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
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("token").asText();
    }

    private UUID seedAllergen(String code, String name, String category) {
        CatalogAllergen allergen = new CatalogAllergen();
        allergen.setCode(code);
        allergen.setName(name);
        allergen.setCategory(category);
        allergen.setActive(true);
        return allergenRepository.save(allergen).getId();
    }

    /** @param freeTextAllergies the legacy prose field, or null to leave it unset */
    private String createPatient(String freeTextAllergies) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("code", "BN-ALG-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("fullName", "Benh Nhan " + UUID.randomUUID().toString().substring(0, 8));
        body.put("phone", "0911222333");

        if (freeTextAllergies != null) {
            body.put("allergies", freeTextAllergies);
        }

        String response = mockMvc.perform(post("/api/patients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("id").asText();
    }

    /** Establishes the treating relationship the access rule derives from. */
    private void bookAppointment(String patient, UUID doctor) {
        jdbcTemplate.update("""
                insert into appointment (id, patient_id, doctor_id, scheduled_at, duration_min, status)
                values (gen_random_uuid(), ?::uuid, ?::uuid, now(), 30, 'BOOKED')
                """, patient, doctor);
    }

    private JsonNode record(String token, String patient, Map<String, Object> body,
                            int expectedStatus) throws Exception {
        String response = mockMvc.perform(post("/api/patients/" + patient + "/allergies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();

        return response.isEmpty() ? null : objectMapper.readTree(response);
    }

    private Map<String, Object> allergyBody(String allergenCode, String severity) {
        Map<String, Object> body = new HashMap<>();
        body.put("allergenCode", allergenCode);
        body.put("severity", severity);
        body.put("reaction", "Man do toan than, kho tho");
        return body;
    }

    private JsonNode list(String token, String patient, boolean includeRetired) throws Exception {
        String response = mockMvc.perform(get("/api/patients/" + patient + "/allergies")
                        .queryParam("includeRetired", String.valueOf(includeRetired))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response);
    }

    private JsonNode retire(String token, String patient, String allergyId, String reason,
                            int expectedStatus) throws Exception {
        String response = mockMvc.perform(
                        post("/api/patients/" + patient + "/allergies/" + allergyId + "/retire")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("reason", reason))))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();

        return response.isEmpty() ? null : objectMapper.readTree(response);
    }

    // ---------- requirement 10.1 ----------

    @Nested
    class WhatARecordHolds {

        @Test
        void anAllergyNamesACodedSubstanceAndCarriesItsName() throws Exception {
            JsonNode created = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "SEVERE"), 201);

            // Both, because the code is what a check matches on and the name is
            // what a clinician reads. Sending one means the client looks up the other.
            assertThat(created.get("allergenCode").asText()).isEqualTo(PENICILLIN);
            assertThat(created.get("allergenName").asText()).isEqualTo("Penicillin");
            assertThat(created.get("allergenCategory").asText()).isEqualTo("DRUG");
        }

        @Test
        void itStoresReactionSeverityDateRecorderAndStatus() throws Exception {
            JsonNode created = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "MODERATE"), 201);

            assertThat(created.get("reaction").asText()).isEqualTo("Man do toan than, kho tho");
            assertThat(created.get("severity").asText()).isEqualTo("MODERATE");
            assertThat(created.get("recordedOn").asText())
                    .isEqualTo(LocalDate.now().toString());
            assertThat(created.get("active").asBoolean()).isTrue();

            // The recorder is stored but not published: who wrote a clinical note
            // belongs in the audit trail, not in a field the UI renders.
            UUID id = UUID.fromString(created.get("id").asText());
            assertThat(allergyRepository.findById(id).orElseThrow().getRecordedBy())
                    .isEqualTo(doctorId);
        }

        @Test
        void certaintyDefaultsToReportedRatherThanConfirmed() throws Exception {
            JsonNode created = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "MILD"), 201);

            // Most allergies are what the patient told someone. Defaulting to
            // CONFIRMED would assert a test result nobody ran.
            assertThat(created.get("certainty").asText()).isEqualTo("REPORTED");
        }

        @Test
        void certaintyIsRecordedSeparatelyFromSeverity() throws Exception {
            Map<String, Object> body = allergyBody(PENICILLIN, "SEVERE");
            body.put("certainty", "SUSPECTED");

            JsonNode created = record(doctorToken, patientId, body, 201);

            // A suspected anaphylaxis is a real situation, and it has to be
            // expressible: severe, and not established.
            assertThat(created.get("severity").asText()).isEqualTo("SEVERE");
            assertThat(created.get("certainty").asText()).isEqualTo("SUSPECTED");
        }

        @Test
        void aSubstanceOutsideTheCatalogueIsRefused() throws Exception {
            // The alternative is creating the allergen on the fly, which is how a
            // catalogue fills up with four spellings of penicillin and a check
            // that matches none of them.
            record(doctorToken, patientId, allergyBody("ALG-KHONG-CO", "SEVERE"), 404);

            assertThat(allergyRepository.findActiveByPatient(UUID.fromString(patientId)))
                    .isEmpty();
        }

        @Test
        void severityIsRequired() throws Exception {
            Map<String, Object> body = new HashMap<>();
            body.put("allergenCode", PENICILLIN);
            body.put("reaction", "Man do");

            // No default: severity decides whether prescribing is warned about or
            // stopped, so guessing it is guessing the clinical answer.
            record(doctorToken, patientId, body, 400);
        }

        @Test
        void aRecordingDateInTheFutureIsRefused() throws Exception {
            Map<String, Object> body = allergyBody(PENICILLIN, "SEVERE");
            body.put("recordedOn", LocalDate.now().plusDays(1).toString());

            record(doctorToken, patientId, body, 400);
        }
    }

    // ---------- requirement 10.2 ----------

    @Nested
    class OneLiveRecordPerSubstance {

        @Test
        void aSecondLiveRecordForTheSameSubstanceIsRefused() throws Exception {
            record(doctorToken, patientId, allergyBody(PENICILLIN, "SEVERE"), 201);

            JsonNode conflict = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "MILD"), 409);

            // Named, so the clinician knows which record to edit instead.
            assertThat(conflict.get("detail").asText()).contains("Penicillin");
        }

        @Test
        void theDuplicateIsRefusedEvenWhenItWouldLowerTheSeverity() throws Exception {
            record(doctorToken, patientId, allergyBody(PENICILLIN, "SEVERE"), 201);
            record(doctorToken, patientId, allergyBody(PENICILLIN, "MILD"), 409);

            // This is the reason the rule exists. Two rows for one substance mean
            // the prescribing check's answer depends on which it reads first, so
            // a SEVERE allergy can be made to read as MILD by adding a row rather
            // than by changing one.
            List<PatientAllergy> live =
                    allergyRepository.findActiveByPatient(UUID.fromString(patientId));
            assertThat(live).hasSize(1);
            assertThat(live.get(0).getSeverity()).isEqualTo(AllergySeverity.SEVERE);
        }

        @Test
        void differentSubstancesBothStayLive() throws Exception {
            record(doctorToken, patientId, allergyBody(PENICILLIN, "SEVERE"), 201);
            record(doctorToken, patientId, allergyBody(SULFA, "MILD"), 201);

            assertThat(list(doctorToken, patientId, false)).hasSize(2);
        }

        @Test
        void theSameSubstanceCanBeRecordedAgainAfterTheOldRecordIsRetired()
                throws Exception {
            JsonNode first = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "SEVERE"), 201);
            retire(doctorToken, patientId, first.get("id").asText(),
                    "Test da chuyen am, khong con di ung", 200);

            // The index is partial for this: an allergy ruled out years ago must
            // not stop a new one being recorded when it recurs.
            record(doctorToken, patientId, allergyBody(PENICILLIN, "MODERATE"), 201);

            assertThat(list(doctorToken, patientId, false)).hasSize(1);
            assertThat(list(doctorToken, patientId, true)).hasSize(2);
        }

        @Test
        void theDatabaseRefusesTheDuplicateEvenWhenTheServiceCheckIsBypassed()
                throws Exception {
            record(doctorToken, patientId, allergyBody(PENICILLIN, "SEVERE"), 201);
            UUID allergenId = allergenRepository.findByCode(PENICILLIN).orElseThrow().getId();

            // The service check gives a good error message; it is not what makes
            // the rule hold. Anything writing this table outside the service — an
            // import, a fix-up script, a second instance racing this one — has to
            // hit the same wall.
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    insert into patient_allergy
                        (id, patient_id, allergen_id, severity, certainty, recorded_on, recorded_by)
                    values (gen_random_uuid(), ?::uuid, ?::uuid, 'MILD', 'REPORTED', current_date, ?::uuid)
                    """, patientId, allergenId.toString(), doctorId.toString()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ---------- retiring ----------

    @Nested
    class Retiring {

        @Test
        void retiringKeepsTheRecordAndItsReason() throws Exception {
            JsonNode created = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "SEVERE"), 201);
            String id = created.get("id").asText();

            JsonNode retired = retire(doctorToken, patientId, id,
                    "Test lay da am tinh, da loai tru", 200);

            assertThat(retired.get("active").asBoolean()).isFalse();
            assertThat(retired.get("inactivationReason").asText())
                    .isEqualTo("Test lay da am tinh, da loai tru");

            // Not deleted: that the allergy was believed, and prescribing worked
            // around it, is part of the record.
            assertThat(allergyRepository.findById(UUID.fromString(id))).isPresent();
        }

        @Test
        void aRetiredRecordIsHiddenFromTheLiveListByDefault() throws Exception {
            JsonNode created = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "SEVERE"), 201);
            retire(doctorToken, patientId, created.get("id").asText(),
                    "Da loai tru sau test", 200);

            // Showing retired allergies beside live ones is how a retired one
            // gets acted on.
            assertThat(list(doctorToken, patientId, false)).isEmpty();
            assertThat(list(doctorToken, patientId, true)).hasSize(1);
        }

        @Test
        void aReasonIsRequired() throws Exception {
            JsonNode created = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "SEVERE"), 201);

            // Taking an allergy off the active list changes what can be
            // prescribed, so someone has to answer for it.
            retire(doctorToken, patientId, created.get("id").asText(), "", 400);
            retire(doctorToken, patientId, created.get("id").asText(), "sai", 400);

            assertThat(list(doctorToken, patientId, false)).hasSize(1);
        }

        @Test
        void theDatabaseRefusesARetiredRowWithNoReason() throws Exception {
            JsonNode created = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "SEVERE"), 201);
            String id = created.get("id").asText();

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "update patient_allergy set active = false where id = ?::uuid", id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void retiringTwiceIsRefused() throws Exception {
            JsonNode created = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "SEVERE"), 201);
            String id = created.get("id").asText();
            retire(doctorToken, patientId, id, "Da loai tru sau test", 200);

            // Otherwise the second call overwrites the first one's reason and
            // timestamp, losing when the decision was actually made.
            retire(doctorToken, patientId, id, "Loai tru lan hai", 400);
        }

        @Test
        void anAllergyBelongingToAnotherPatientCannotBeRetiredThroughThisOne()
                throws Exception {
            JsonNode created = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "SEVERE"), 201);

            String otherPatient = createPatient(null);
            bookAppointment(otherPatient, doctorId);

            // The id in the path is not enough on its own; it has to belong to the
            // patient named in the path.
            retire(doctorToken, otherPatient, created.get("id").asText(),
                    "Khong thuoc benh nhan nay", 404);
        }
    }

    // ---------- ordering, and the severe-allergy signal ----------

    @Nested
    class SevereFirst {

        @Test
        void theWorstAllergyIsFirstInTheList() throws Exception {
            record(doctorToken, patientId, allergyBody(SULFA, "MILD"), 201);
            record(doctorToken, patientId, allergyBody(PENICILLIN, "SEVERE"), 201);

            JsonNode listed = list(doctorToken, patientId, false);

            // Insertion order would have put the severe one second. The ordering
            // is spelled out in the query rather than left to the enum's
            // alphabetical accident, so a new level cannot sort itself below MILD.
            assertThat(listed.get(0).get("allergenCode").asText()).isEqualTo(PENICILLIN);
            assertThat(listed.get(1).get("allergenCode").asText()).isEqualTo(SULFA);
        }

        @Test
        void theResponseMarksSevereRecordsForTheWarningBanner() throws Exception {
            JsonNode severe = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "SEVERE"), 201);
            JsonNode mild = record(doctorToken, patientId, allergyBody(SULFA, "MILD"), 201);

            // Derived from severity rather than stored, so it cannot drift from it.
            assertThat(severe.get("severe").asBoolean()).isTrue();
            assertThat(mild.get("severe").asBoolean()).isFalse();
        }

        @Test
        void thePatientLevelSevereCheckIgnoresRetiredRecords() throws Exception {
            UUID patient = UUID.fromString(patientId);
            assertThat(allergyService.hasSevereAllergy(patient)).isFalse();

            JsonNode created = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "SEVERE"), 201);
            assertThat(allergyService.hasSevereAllergy(patient)).isTrue();

            retire(doctorToken, patientId, created.get("id").asText(),
                    "Da loai tru sau test", 200);
            assertThat(allergyService.hasSevereAllergy(patient)).isFalse();
        }
    }

    // ---------- requirement 10.3 ----------

    @Nested
    class LegacyFreeText {

        /**
         * Runs migration V13's own update statement, read from the migration file,
         * against a patient whose prose allergies are already there.
         *
         * <p>Flyway ran V13 against an empty schema, so the migration's effect on
         * real data cannot be observed by letting it run. Taking the statement
         * from the file rather than restating it here is what makes this a test of
         * the migration: edit the statement and this fails.
         */
        private String legacyMigrationStatement() throws Exception {
            String sql = Files.readString(
                    Path.of("src/main/resources/db/migration/V13__patient_allergy.sql"),
                    StandardCharsets.UTF_8);

            int start = sql.indexOf("update patient");
            assertThat(start)
                    .withFailMessage("V13 no longer contains the legacy allergy migration")
                    .isGreaterThan(-1);

            return sql.substring(start, sql.indexOf(';', start) + 1);
        }

        @Test
        void theProseIsMovedToANoteAndThePatientIsFlagged() throws Exception {
            String patient = createPatient(null);
            jdbcTemplate.update(
                    "update patient set allergies = ?, allergy_review_required = false, "
                            + "allergy_legacy_note = null where id = ?::uuid",
                    "Di ung penicillin, hai san", patient);

            jdbcTemplate.execute(legacyMigrationStatement());

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "select allergy_legacy_note, allergy_review_required "
                            + "from patient where id = ?::uuid", patient);

            // Verbatim: a reviewer needs the words the clinic actually recorded,
            // not a normalised version of them.
            assertThat(row.get("allergy_legacy_note"))
                    .isEqualTo("Di ung penicillin, hai san");
            assertThat(row.get("allergy_review_required")).isEqualTo(true);
        }

        @Test
        void noCodedAllergyIsInventedFromTheProse() throws Exception {
            String patient = createPatient(null);
            jdbcTemplate.update(
                    "update patient set allergies = ? where id = ?::uuid",
                    "Di ung penicillin", patient);

            jdbcTemplate.execute(legacyMigrationStatement());

            // The whole point. A parser would get most rows right and the rows it
            // got wrong would be invisible: "khong di ung thuoc" becoming an
            // allergy record, or "di ung khang sinh" becoming penicillin alone.
            // Neither error announces itself, and both are clinical.
            assertThat(allergyRepository.findActiveByPatient(UUID.fromString(patient)))
                    .isEmpty();
        }

        @Test
        void aPatientWithNoProseIsNotFlagged() throws Exception {
            String patient = createPatient(null);

            jdbcTemplate.execute(legacyMigrationStatement());

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "select allergy_review_required from patient where id = ?::uuid", patient);

            // Flagging everyone would make the flag mean nothing, and the review
            // queue would be the patient list.
            assertThat(row.get("allergy_review_required")).isEqualTo(false);
        }

        @Test
        void whitespaceOnlyProseIsNotTreatedAsAllergyInformation() throws Exception {
            String patient = createPatient(null);
            jdbcTemplate.update(
                    "update patient set allergies = ?, allergy_review_required = false "
                            + "where id = ?::uuid", "   ", patient);

            jdbcTemplate.execute(legacyMigrationStatement());

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "select allergy_review_required from patient where id = ?::uuid", patient);

            assertThat(row.get("allergy_review_required")).isEqualTo(false);
        }

        @Test
        void theOriginalColumnIsLeftInPlaceSoTheMoveCanBeChecked() throws Exception {
            // Dropping it in the same release that moves the data removes the only
            // way to verify the move was faithful. A later migration drops it.
            Integer columns = jdbcTemplate.queryForObject("""
                    select count(*) from information_schema.columns
                    where table_name = 'patient' and column_name = 'allergies'
                    """, Integer.class);

            assertThat(columns).isEqualTo(1);
        }

        @Test
        void proseTypedInTodayIsFlaggedTheSameWayAsMigratedProse() throws Exception {
            // The migrated-from field is still writable, so without this a
            // sentence typed in today would land in exactly the state the
            // migration exists to flag, carrying no mark saying so.
            String patient = createPatient("Di ung thuoc khang sinh");

            String body = mockMvc.perform(get("/api/patients/" + patient)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(objectMapper.readTree(body).get("allergyReviewRequired").asBoolean())
                    .isTrue();
        }

        @Test
        void aClinicianConfirmingTheReviewClearsTheFlag() throws Exception {
            String patient = createPatient("Di ung penicillin");
            bookAppointment(patient, doctorId);

            mockMvc.perform(post("/api/patients/" + patient + "/allergies/mark-reviewed")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorToken))
                    .andExpect(status().isNoContent());

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "select allergy_review_required, allergy_legacy_note "
                            + "from patient where id = ?::uuid", patient);

            assertThat(row.get("allergy_review_required")).isEqualTo(false);
        }

        @Test
        void aReceptionistCannotClearTheReviewFlag() throws Exception {
            String patient = createPatient("Di ung penicillin");

            // Clearing it asserts that someone qualified read the text. A
            // receptionist cannot see clinical data, so they could only do it blind.
            mockMvc.perform(post("/api/patients/" + patient + "/allergies/mark-reviewed")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistToken))
                    .andExpect(status().isForbidden());

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "select allergy_review_required from patient where id = ?::uuid", patient);
            assertThat(row.get("allergy_review_required")).isEqualTo(true);
        }
    }

    // ---------- who may read and write allergies ----------

    @Nested
    class Access {

        @Test
        void aDoctorWithNoTreatingRelationshipIsRefused() throws Exception {
            mockMvc.perform(get("/api/patients/" + patientId + "/allergies")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherDoctorToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aReceptionistCannotReadAllergies() throws Exception {
            // Allergies are clinical data, and the receptionist's view of a
            // patient deliberately has no clinical fields at all.
            mockMvc.perform(get("/api/patients/" + patientId + "/allergies")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + receptionistToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aReceptionistCannotRecordAnAllergy() throws Exception {
            record(receptionistToken, patientId, allergyBody(PENICILLIN, "SEVERE"), 403);

            assertThat(allergyRepository.findActiveByPatient(UUID.fromString(patientId)))
                    .isEmpty();
        }

        @Test
        void anUnauthenticatedCallerIsRefused() throws Exception {
            mockMvc.perform(get("/api/patients/" + patientId + "/allergies"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ---------- the audit trail ----------

    @Nested
    class AuditTrail {

        @Test
        void recordingAnAllergyIsAuditedWithTheSubstanceAndSeverity() throws Exception {
            JsonNode created = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "SEVERE"), 201);

            Map<String, Object> entry = jdbcTemplate.queryForMap("""
                    select action, entity_type, detail::text as detail
                    from audit_log
                    where entity_type = 'patient_allergy' and entity_id = ?::uuid
                    order by created_at desc limit 1
                    """, created.get("id").asText());

            assertThat(entry.get("action")).isEqualTo("CREATE");

            // Both are codes from a fixed set, not free text about the patient,
            // and that a SEVERE allergy was added is the reason to audit this.
            assertThat(entry.get("detail").toString())
                    .contains(PENICILLIN)
                    .contains("SEVERE");
        }

        @Test
        void anAdminReadingAllergiesIsRecorded() throws Exception {
            record(doctorToken, patientId, allergyBody(PENICILLIN, "SEVERE"), 201);
            list(adminToken, patientId, false);

            // Requirement 8.7: administrative access to clinical data is the
            // access least likely to be clinically necessary, so it is the access
            // most worth being able to review afterwards.
            Integer views = jdbcTemplate.queryForObject("""
                    select count(*) from audit_log
                    where entity_type = 'patient_allergy' and entity_id = ?::uuid
                      and action = 'VIEW'
                    """, Integer.class, patientId);

            assertThat(views).isEqualTo(1);
        }

        @Test
        void aDoctorReadingTheirOwnPatientsAllergiesIsNotRecorded() throws Exception {
            record(doctorToken, patientId, allergyBody(PENICILLIN, "SEVERE"), 201);
            list(doctorToken, patientId, false);

            // Recording every routine clinical read would bury the entries that
            // matter under the ones that do not.
            Integer views = jdbcTemplate.queryForObject("""
                    select count(*) from audit_log
                    where entity_type = 'patient_allergy' and entity_id = ?::uuid
                      and action = 'VIEW'
                    """, Integer.class, patientId);

            assertThat(views).isZero();
        }

        @Test
        void retiringAnAllergyIsAudited() throws Exception {
            JsonNode created = record(doctorToken, patientId,
                    allergyBody(PENICILLIN, "SEVERE"), 201);
            String id = created.get("id").asText();
            retire(doctorToken, patientId, id, "Da loai tru sau test", 200);

            Integer updates = jdbcTemplate.queryForObject("""
                    select count(*) from audit_log
                    where entity_type = 'patient_allergy' and entity_id = ?::uuid
                      and action = 'UPDATE'
                    """, Integer.class, id);

            assertThat(updates).isEqualTo(1);
        }
    }
}
