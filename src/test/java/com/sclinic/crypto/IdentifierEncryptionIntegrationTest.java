package com.sclinic.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclinic.patient.Patient;
import com.sclinic.patient.PatientRepository;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Patient identity numbers, encrypted at rest, against a real database.
 *
 * <p>The property that matters is requirement 7.4: a direct query against the
 * database must not return these values in the clear. So every assertion here
 * reads the column with {@code JdbcTemplate}, bypassing the JPA converter, which
 * is what a stolen backup or a compromised database account would see.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-username=admin",
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-CRYPTO-001",
        "sclinic.auth.mfa-required-roles=",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
class IdentifierEncryptionIntegrationTest {

    private static final String ADMIN_PASSWORD = "AdminKham2026!Test";

    /**
     * Each test gets its own identity number. The identity column is uniquely
     * indexed through its blind index, which is the behaviour under test, so
     * sharing one number across tests in this shared database would mean every
     * test after the first collides on it.
     */
    private static final java.util.concurrent.atomic.AtomicInteger SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger(1);

    /** The identifiers one test registered, so it can assert on the values it used. */
    private record Identifiers(String patientId, String nationalId, String insuranceNo,
                               String taxCode) {
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrlFor("crypto_it"));
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
    PatientRepository patientRepository;

    @Autowired
    BlindIndex blindIndex;

    @Autowired
    AesGcmCipher cipher;

    @Autowired
    IdentifierEncryptionJob encryptionJob;

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
    }

    private Identifiers createPatientWithIdentifiers(String code) throws Exception {
        int n = SEQUENCE.getAndIncrement();
        String nationalId = String.format("0011990%05d", n);
        String insuranceNo = String.format("DN479%010d", n);
        String taxCode = String.format("80%08d", n);

        String patientId = createPatient(code, nationalId, insuranceNo, taxCode);
        return new Identifiers(patientId, nationalId, insuranceNo, taxCode);
    }

    private String createPatient(String code, String nationalId, String insuranceNo,
                                 String taxCode) throws Exception {
        String body = mockMvc.perform(post("/api/patients")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "fullName", "Nguyen Van Crypto",
                                "nationalId", nationalId,
                                "insuranceNo", insuranceNo,
                                "taxCode", taxCode))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    /** Reads the raw column, bypassing the JPA converter. */
    private Map<String, Object> rawRow(String patientId) {
        return jdbcTemplate.queryForMap("""
                select national_id_enc, insurance_no_enc, tax_code_enc, national_id_hash
                  from patient where id = ?::uuid
                """, patientId);
    }

    @Nested
    class ThroughTheApi {

        @Test
        void returnsTheIdentifiersInTheClear() throws Exception {
            Identifiers created = createPatientWithIdentifiers("BN-ENC-1");

            mockMvc.perform(get("/api/patients/" + created.patientId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nationalId").value(created.nationalId()))
                    .andExpect(jsonPath("$.insuranceNo").value(created.insuranceNo()))
                    .andExpect(jsonPath("$.taxCode").value(created.taxCode()));
        }

        @Test
        void survivesAnUpdate() throws Exception {
            Identifiers created = createPatientWithIdentifiers("BN-ENC-2");
            String newNumber = "001199099999";

            mockMvc.perform(put("/api/patients/" + created.patientId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "fullName", "Nguyen Van Crypto",
                                    "nationalId", newNumber))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nationalId").value(newNumber));

            assertThat(rawRow(created.patientId()).get("national_id_enc").toString())
                    .doesNotContain(newNumber);
        }

        @Test
        void rewritesTheLookupIndexWhenTheNumberChanges() throws Exception {
            Identifiers created = createPatientWithIdentifiers("BN-ENC-2b");
            String newNumber = "001199088888";

            mockMvc.perform(put("/api/patients/" + created.patientId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "fullName", "Nguyen Van Crypto",
                                    "nationalId", newNumber))))
                    .andExpect(status().isOk());

            // Findable by the new number, and no longer by the old one.
            assertThat(patientRepository
                    .findByNationalIdHashAndDeletedAtIsNull(blindIndex.of(newNumber)))
                    .isPresent();
            assertThat(patientRepository
                    .findByNationalIdHashAndDeletedAtIsNull(blindIndex.of(created.nationalId())))
                    .isEmpty();
        }

        @Test
        void roundTripsThroughTheRepositoryToo() throws Exception {
            Identifiers created = createPatientWithIdentifiers("BN-ENC-3");

            Patient patient = patientRepository
                    .findById(UUID.fromString(created.patientId())).orElseThrow();

            assertThat(patient.getNationalId()).isEqualTo(created.nationalId());
            assertThat(patient.getInsuranceNo()).isEqualTo(created.insuranceNo());
            assertThat(patient.getTaxCode()).isEqualTo(created.taxCode());
        }
    }

    // ---------- requirement 7.4 ----------

    @Nested
    class StraightFromTheDatabase {

        @Test
        void noIdentifierIsReadableInTheClear() throws Exception {
            Identifiers created = createPatientWithIdentifiers("BN-RAW-1");

            Map<String, Object> row = rawRow(created.patientId());

            assertThat(row.get("national_id_enc").toString())
                    .doesNotContain(created.nationalId());
            assertThat(row.get("insurance_no_enc").toString())
                    .doesNotContain(created.insuranceNo());
            assertThat(row.get("tax_code_enc").toString()).doesNotContain(created.taxCode());
        }

        @Test
        void theStoredFormIsRecognisableAsEncrypted() throws Exception {
            Identifiers created = createPatientWithIdentifiers("BN-RAW-2");

            assertThat(row(created.patientId(), "national_id_enc")).startsWith("v1:");
        }

        /**
         * Scans the whole table for the plaintext rather than just the column it
         * is supposed to be in: a value copied into some other column by a future
         * mapping mistake would still be a leak.
         */
        @Test
        void thePlaintextAppearsNowhereInTheTable() throws Exception {
            Identifiers created = createPatientWithIdentifiers("BN-RAW-3");

            Long matches = jdbcTemplate.queryForObject(
                    "select count(*) from patient where patient::text like ?",
                    Long.class, "%" + created.nationalId() + "%");

            assertThat(matches).isZero();
        }

        @Test
        void theLookupIndexDoesNotRevealTheNumberEither() throws Exception {
            Identifiers created = createPatientWithIdentifiers("BN-RAW-4");

            assertThat(row(created.patientId(), "national_id_hash"))
                    .isNotNull()
                    .doesNotContain(created.nationalId());
        }

        private String row(String patientId, String column) {
            return jdbcTemplate.queryForObject(
                    "select " + column + " from patient where id = ?::uuid",
                    String.class, patientId);
        }
    }

    // ---------- exact lookup still works ----------

    @Nested
    class LookupByNationalId {

        @Test
        void findsThePatientThroughTheBlindIndex() throws Exception {
            Identifiers created = createPatientWithIdentifiers("BN-FIND-1");

            var found = patientRepository
                    .findByNationalIdHashAndDeletedAtIsNull(blindIndex.of(created.nationalId()));

            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(UUID.fromString(created.patientId()));
            // And the value comes back decrypted.
            assertThat(found.get().getNationalId()).isEqualTo(created.nationalId());
        }

        @Test
        void findsNothingForADifferentNumber() throws Exception {
            createPatientWithIdentifiers("BN-FIND-2");

            assertThat(patientRepository
                    .findByNationalIdHashAndDeletedAtIsNull(blindIndex.of("009999000000")))
                    .isEmpty();
        }

        /**
         * Two patients must not end up holding the same CCCD. Enforced by a
         * unique index on the blind index column, which is the only way to state
         * the constraint once the value itself is encrypted differently each time.
         */
        @Test
        void refusesASecondPatientWithTheSameNumber() throws Exception {
            Identifiers first = createPatientWithIdentifiers("BN-FIND-3");

            assertThatThrownBy(() -> createPatient("BN-FIND-4", first.nationalId(),
                    "DN479other", "80other"))
                    .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
        }

        /**
         * The unique index is partial. Most patients have no CCCD on file, and in
         * a plain unique index every one of those would collide with the next.
         */
        @Test
        void allowsManyPatientsWithNoNumberAtAll() throws Exception {
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(post("/api/patients")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "code", "BN-NONE-" + i,
                                        "fullName", "Khong co CCCD " + i))))
                        .andExpect(status().isCreated());
            }
        }
    }

    // ---------- the one-off conversion ----------

    /**
     * Covers the deployment that already holds patient data. The plaintext
     * columns still exist at this point: V9 only adds the encrypted ones, and
     * dropping the originals is a later release, which is the window in which a
     * conversion that went wrong can still be diagnosed.
     */
    @Nested
    class ConvertingExistingData {

        private String legacyNationalId;
        private String legacyInsuranceNo;
        private String legacyTaxCode;

        /** Writes a row the way it looked before encryption existed. */
        private UUID insertLegacyRow(String code) {
            int n = SEQUENCE.getAndIncrement();
            legacyNationalId = String.format("0011990%05d", n);
            legacyInsuranceNo = String.format("DN479%010d", n);
            legacyTaxCode = String.format("80%08d", n);

            UUID id = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into patient (id, code, full_name, national_id, insurance_no, tax_code)
                    values (?::uuid, ?, ?, ?, ?, ?)
                    """, id, code, "Benh nhan cu", legacyNationalId, legacyInsuranceNo,
                    legacyTaxCode);
            return id;
        }

        @Test
        void encryptsWhatWasLeftInThePlaintextColumns() {
            UUID id = insertLegacyRow("BN-LEGACY-1");

            encryptionJob.run();

            Map<String, Object> row = rawRow(id.toString());
            assertThat(row.get("national_id_enc").toString()).startsWith("v1:");
            assertThat(cipher.decrypt(row.get("national_id_enc").toString())).isEqualTo(legacyNationalId);
            assertThat(cipher.decrypt(row.get("insurance_no_enc").toString())).isEqualTo(legacyInsuranceNo);
            assertThat(cipher.decrypt(row.get("tax_code_enc").toString())).isEqualTo(legacyTaxCode);
        }

        @Test
        void buildsTheLookupIndexForConvertedRows() {
            UUID id = insertLegacyRow("BN-LEGACY-2");

            encryptionJob.run();

            assertThat(rawRow(id.toString()).get("national_id_hash"))
                    .isEqualTo(blindIndex.of(legacyNationalId));
        }

        @Test
        void losesNoRecords() {
            Long before = jdbcTemplate.queryForObject("select count(*) from patient", Long.class);
            insertLegacyRow("BN-LEGACY-3");

            encryptionJob.run();

            Long after = jdbcTemplate.queryForObject("select count(*) from patient", Long.class);
            assertThat(after).isEqualTo(before + 1);
        }

        /** Restarting the application must not re-encrypt what is already done. */
        @Test
        void isIdempotent() {
            UUID id = insertLegacyRow("BN-LEGACY-4");

            encryptionJob.run();
            String afterFirstRun = rawRow(id.toString()).get("national_id_enc").toString();
            encryptionJob.run();
            String afterSecondRun = rawRow(id.toString()).get("national_id_enc").toString();

            // Unchanged, so a restart cannot produce a double-encrypted value.
            assertThat(afterSecondRun).isEqualTo(afterFirstRun);
            assertThat(cipher.decrypt(afterSecondRun)).isEqualTo(legacyNationalId);
        }

        @Test
        void leavesTheOriginalsInPlaceForVerification() {
            UUID id = insertLegacyRow("BN-LEGACY-5");

            encryptionJob.run();

            String stillThere = jdbcTemplate.queryForObject(
                    "select national_id from patient where id = ?::uuid", String.class, id);
            assertThat(stillThere).isEqualTo(legacyNationalId);
        }

        /** The check that gates deploying V10. */
        @Test
        void leavesNothingUnconvertedAfterwards() {
            insertLegacyRow("BN-LEGACY-6");

            encryptionJob.run();

            Long unconverted = jdbcTemplate.queryForObject("""
                    select count(*) from patient
                     where (national_id  is not null and national_id_enc  is null)
                        or (insurance_no is not null and insurance_no_enc is null)
                        or (tax_code     is not null and tax_code_enc     is null)
                    """, Long.class);
            assertThat(unconverted).isZero();
        }

        @Test
        void readsAnUnconvertedRowWithoutFailing() {
            UUID id = insertLegacyRow("BN-LEGACY-7");
            // Simulates the window between the migration and the conversion: the
            // encrypted column is empty and the plaintext one holds the value.
            jdbcTemplate.update(
                    "update patient set national_id_enc = national_id where id = ?::uuid", id);

            Patient patient = patientRepository.findById(id).orElseThrow();

            assertThat(patient.getNationalId()).isEqualTo(legacyNationalId);
        }

        @Test
        void convertsMoreRowsThanOneBatch() {
            List<String> codes = java.util.stream.IntStream.range(0, 3)
                    .mapToObj(i -> "BN-BATCH-" + i)
                    .toList();
            codes.forEach(code -> {
                UUID id = UUID.randomUUID();
                jdbcTemplate.update("""
                        insert into patient (id, code, full_name, insurance_no)
                        values (?::uuid, ?, ?, ?)
                        """, id, code, "Benh nhan " + code, "BH-" + code);
            });

            encryptionJob.run();

            Long unconverted = jdbcTemplate.queryForObject(
                    "select count(*) from patient where insurance_no is not null"
                            + " and insurance_no_enc is null", Long.class);
            assertThat(unconverted).isZero();
        }
    }
}
