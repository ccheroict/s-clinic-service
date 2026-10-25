package com.sclinic.encounter;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two properties of a signed record.
 *
 * <p>The first: no single change to its content leaves the hash the same. That is
 * what makes verification able to detect tampering at all, and it has to hold for
 * every field rather than for the ones somebody thought to test — a field left
 * out of the canonical form is a field that can be edited silently, and the gap
 * is invisible because the signature still verifies.
 *
 * <p>The second: changing the things that are deliberately <em>not</em> content
 * must <em>not</em> change the hash. Retiring a record, recalculating its
 * retention date, or marking it AMENDED because a correction was signed are all
 * administration, and if any of them altered the hash then doing the right thing
 * would make a record look tampered with.
 *
 * <p>Both are about the canonical form rather than the database, so they run
 * without one. The database half of requirement 14.6 — that the triggers actually
 * refuse the edits — is in {@code EncounterSigningIntegrationTest}.
 */
class EncounterImmutabilityPropertyTest {

    private static final UUID PATIENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOCTOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SCHEMA = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant AT = Instant.parse("2026-10-25T02:30:00.123456Z");

    private Encounter signedRecord() {
        Encounter encounter = new Encounter();
        encounter.setId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        encounter.setPatientId(PATIENT);
        encounter.setDoctorId(DOCTOR);
        encounter.setAppointmentId(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        encounter.setEncounteredAt(AT);
        encounter.setReason("Ngứa, mẩn đỏ hai cẳng tay");
        encounter.setTreatmentPlan("Bôi corticoid nhẹ 7 ngày");
        encounter.setAdvice("Tránh tắm nước quá nóng");
        encounter.setFollowUpDate(LocalDate.of(2026, 11, 8));
        encounter.setReferralNote(null);
        encounter.setClinicalDataSchemaId(SCHEMA);
        encounter.setClinicalData(new java.util.HashMap<>(Map.of(
                "severity", "MODERATE",
                "itchScore", 6,
                "lesionTypes", List.of("PLAQUE", "SCALE"))));
        encounter.setStatus(EncounterStatus.SIGNED);
        return encounter;
    }

    private List<EncounterDiagnosis> diagnoses() {
        EncounterDiagnosis primary = new EncounterDiagnosis();
        primary.setId(UUID.randomUUID());
        primary.setIcd10Code("L20.9");
        primary.setIcd10Name("Viêm da cơ địa");
        primary.setPrimary(true);

        EncounterDiagnosis secondary = new EncounterDiagnosis();
        secondary.setId(UUID.randomUUID());
        secondary.setIcd10Code("L40.0");
        secondary.setIcd10Name("Vảy nến thể mảng");
        secondary.setPrimary(false);

        List<EncounterDiagnosis> list = new ArrayList<>();
        list.add(primary);
        list.add(secondary);
        return list;
    }

    private String hashOf(Encounter encounter, List<EncounterDiagnosis> diagnoses) {
        return EncounterCanonicalForm.hash(encounter, diagnoses);
    }

    // ---------- no content change survives ----------

    @Property(tries = 50)
    void noSingleContentChangeLeavesTheHashUnchanged(
            @ForAll("contentEdits") ContentEdit edit) {

        Encounter encounter = signedRecord();
        List<EncounterDiagnosis> diagnoses = diagnoses();
        String before = hashOf(encounter, diagnoses);

        edit.apply(encounter);

        // If this fails, the edited field is missing from the canonical form -
        // which means it can be changed on a signed record and verification will
        // still say the record is intact.
        assertThat(hashOf(encounter, diagnoses))
                .withFailMessage("Editing %s did not change the hash, so that field "
                        + "is not covered by the signature", edit.name())
                .isNotEqualTo(before);
    }

    @Property(tries = 50)
    void noSingleDiagnosisChangeLeavesTheHashUnchanged(
            @ForAll("diagnosisEdits") DiagnosisEdit edit) {

        Encounter encounter = signedRecord();
        List<EncounterDiagnosis> diagnoses = diagnoses();
        String before = hashOf(encounter, diagnoses);

        edit.apply(diagnoses);

        // The diagnosis is the part of a record most worth changing after the
        // fact, and it lives in a different table, so it is the easiest thing to
        // leave out of the hash by accident.
        assertThat(hashOf(encounter, diagnoses))
                .withFailMessage("Editing diagnoses by %s did not change the hash",
                        edit.name())
                .isNotEqualTo(before);
    }

    // ---------- administration does not look like tampering ----------

    @Test
    void markingTheRecordAmendedDoesNotChangeTheHash() {
        Encounter encounter = signedRecord();
        List<EncounterDiagnosis> diagnoses = diagnoses();
        String before = hashOf(encounter, diagnoses);

        encounter.setStatus(EncounterStatus.AMENDED);

        // The reason status is excluded. If it were hashed, signing a correction
        // would break the original's own signature, and a record would appear
        // tampered with by the act of being properly corrected.
        assertThat(hashOf(encounter, diagnoses)).isEqualTo(before);
    }

    @Test
    void retiringTheRecordDoesNotChangeTheHash() {
        Encounter encounter = signedRecord();
        List<EncounterDiagnosis> diagnoses = diagnoses();
        String before = hashOf(encounter, diagnoses);

        encounter.setDeletedAt(Instant.now());
        encounter.setDeletedBy(DOCTOR);
        encounter.setDeleteReason("Trùng bản ghi");
        encounter.setRetentionUntil(LocalDate.of(2050, 1, 1));
        encounter.setLegalHold(true);
        encounter.setUpdatedAt(Instant.now());

        // Archival administration says nothing about what the doctor found.
        // Hashing it would make an archivist's work look like a clinical edit.
        assertThat(hashOf(encounter, diagnoses)).isEqualTo(before);
    }

    // ---------- the hash is stable in the ways it must be ----------

    @Test
    void theSameRecordHashesTheSameTwice() {
        // Obvious, and the one failure that would make every signature in the
        // clinic unverifiable, so it is worth a test of its own.
        assertThat(hashOf(signedRecord(), diagnoses()))
                .isEqualTo(hashOf(signedRecord(), diagnoses()));
    }

    @Test
    void diagnosisOrderDoesNotChangeTheHash() {
        Encounter encounter = signedRecord();
        List<EncounterDiagnosis> forward = diagnoses();
        List<EncounterDiagnosis> reversed = diagnoses();
        java.util.Collections.reverse(reversed);

        // The database does not promise a row order, so a hash that depended on
        // one would fail verification at random.
        assertThat(hashOf(encounter, reversed)).isEqualTo(hashOf(encounter, forward));
    }

    @Test
    void clinicalDataKeyOrderDoesNotChangeTheHash() {
        Encounter first = signedRecord();
        Encounter second = signedRecord();

        java.util.LinkedHashMap<String, Object> reordered = new java.util.LinkedHashMap<>();
        reordered.put("itchScore", 6);
        reordered.put("lesionTypes", List.of("PLAQUE", "SCALE"));
        reordered.put("severity", "MODERATE");
        second.setClinicalData(reordered);

        // A jsonb column does not return keys in the order they were written, so
        // the same data read back twice can arrive differently ordered. Without
        // sorting, a record would fail to verify against its own signature for no
        // reason at all.
        assertThat(hashOf(second, diagnoses())).isEqualTo(hashOf(first, diagnoses()));
    }

    @Test
    void aNumberReadBackAsADoubleHashesTheSameAsTheIntegerWritten() {
        Encounter written = signedRecord();
        Encounter readBack = signedRecord();

        java.util.HashMap<String, Object> asDouble =
                new java.util.HashMap<>(written.getClinicalData());
        asDouble.put("itchScore", 6.0);
        readBack.setClinicalData(asDouble);

        // Jackson may hand back an Integer or a Double for the same stored
        // number. Hashing the two differently would make verification depend on
        // which type the driver happened to choose.
        assertThat(hashOf(readBack, diagnoses())).isEqualTo(hashOf(written, diagnoses()));
    }

    @Test
    void anEmptyFieldIsNotTheSameAsAnAbsentOne() {
        Encounter empty = signedRecord();
        empty.setAdvice("");

        Encounter absent = signedRecord();
        absent.setAdvice(null);

        // The length prefix is what makes this hold. With a separator, "no advice
        // given" and "advice recorded as nothing" would be indistinguishable, and
        // in a medical record those are different statements.
        assertThat(hashOf(empty, diagnoses())).isNotEqualTo(hashOf(absent, diagnoses()));
    }

    @Test
    void fieldContentCannotImitateTheFieldBoundary() {
        Encounter shifted = signedRecord();
        shifted.setReason("a");
        shifted.setTreatmentPlan("b");

        Encounter crafted = signedRecord();
        crafted.setReason("a:b");
        crafted.setTreatmentPlan(null);

        // The attack the length prefix exists to stop: with a delimiter, a value
        // containing the delimiter could be rewritten as a different split across
        // two fields while hashing identically.
        assertThat(hashOf(crafted, diagnoses())).isNotEqualTo(hashOf(shifted, diagnoses()));
    }

    // ---------- generators ----------

    record ContentEdit(String name, Consumer<Encounter> edit) {
        void apply(Encounter encounter) {
            edit.accept(encounter);
        }
    }

    record DiagnosisEdit(String name, Consumer<List<EncounterDiagnosis>> edit) {
        void apply(List<EncounterDiagnosis> diagnoses) {
            edit.accept(diagnoses);
        }
    }

    @Provide
    Arbitrary<ContentEdit> contentEdits() {
        return Arbitraries.of(
                new ContentEdit("patientId",
                        e -> e.setPatientId(UUID.randomUUID())),
                new ContentEdit("doctorId",
                        e -> e.setDoctorId(UUID.randomUUID())),
                new ContentEdit("appointmentId",
                        e -> e.setAppointmentId(UUID.randomUUID())),
                new ContentEdit("appointmentId cleared",
                        e -> e.setAppointmentId(null)),
                new ContentEdit("encounteredAt",
                        e -> e.setEncounteredAt(AT.plusSeconds(1))),
                new ContentEdit("reason",
                        e -> e.setReason("Lý do khác")),
                new ContentEdit("reason cleared",
                        e -> e.setReason(null)),
                new ContentEdit("treatmentPlan",
                        e -> e.setTreatmentPlan("Phác đồ khác")),
                new ContentEdit("advice",
                        e -> e.setAdvice("Lời dặn khác")),
                new ContentEdit("followUpDate",
                        e -> e.setFollowUpDate(LocalDate.of(2027, 1, 1))),
                new ContentEdit("followUpDate cleared",
                        e -> e.setFollowUpDate(null)),
                new ContentEdit("referralNote added",
                        e -> e.setReferralNote("Chuyển tuyến trên")),
                new ContentEdit("clinicalDataSchemaId",
                        e -> e.setClinicalDataSchemaId(UUID.randomUUID())),
                new ContentEdit("clinical severity",
                        e -> e.getClinicalData().put("severity", "SEVERE")),
                new ContentEdit("clinical score",
                        e -> e.getClinicalData().put("itchScore", 9)),
                new ContentEdit("clinical field added",
                        e -> e.getClinicalData().put("notes", "thêm")),
                new ContentEdit("clinical field removed",
                        e -> e.getClinicalData().remove("severity")),
                new ContentEdit("clinical list reordered",
                        e -> e.getClinicalData().put("lesionTypes",
                                List.of("SCALE", "PLAQUE"))),
                new ContentEdit("clinical data cleared",
                        e -> e.setClinicalData(null)),
                new ContentEdit("amendsEncounterId",
                        e -> e.setAmendsEncounterId(UUID.randomUUID())),
                new ContentEdit("amendmentReason",
                        e -> {
                            e.setAmendsEncounterId(UUID.randomUUID());
                            e.setAmendmentReason("Lý do đính chính");
                        }));
    }

    @Provide
    Arbitrary<DiagnosisEdit> diagnosisEdits() {
        return Arbitraries.of(
                new DiagnosisEdit("changing a code",
                        list -> list.get(0).setIcd10Code("L99.9")),
                new DiagnosisEdit("changing a name",
                        list -> list.get(0).setIcd10Name("Tên khác")),
                new DiagnosisEdit("moving the primary flag",
                        list -> {
                            list.get(0).setPrimary(false);
                            list.get(1).setPrimary(true);
                        }),
                new DiagnosisEdit("adding a note",
                        list -> list.get(0).setNote("Ghi chú thêm")),
                new DiagnosisEdit("removing a diagnosis",
                        list -> list.remove(1)),
                new DiagnosisEdit("adding a diagnosis",
                        list -> {
                            EncounterDiagnosis extra = new EncounterDiagnosis();
                            extra.setIcd10Code("L30.0");
                            extra.setIcd10Name("Chàm");
                            extra.setPrimary(false);
                            list.add(extra);
                        }));
    }
}
