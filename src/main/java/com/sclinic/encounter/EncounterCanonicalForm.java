package com.sclinic.encounter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Exactly which bytes of an encounter are signed.
 *
 * <p>This is the most unforgiving piece of the signing work, because its output
 * has to be reproducible for as long as the record is kept. A signature proves
 * that these bytes have not changed; if the recipe for producing them drifts by a
 * character, every signature ever made stops verifying, and the failure looks
 * exactly like the records having been tampered with.
 *
 * <p><b>Length-prefixed, as the audit chain is.</b> Each part is written as its
 * character length, a colon, then the part. A delimiter can be imitated by field
 * content: with a separator, {@code reason="a|b", advice=null} and
 * {@code reason="a", advice="b"} would hash identically, so one could be rewritten
 * as the other without breaking the signature. With a length prefix the
 * decomposition is unique whatever the fields contain. Null is {@code -1:}, which
 * no real string produces, so an empty field is never confused with an absent one.
 *
 * <p><b>What is deliberately excluded, and why each one matters:</b>
 *
 * <ul>
 *   <li>{@code status}. The original's status changes to AMENDED when a
 *       correction is signed. Hashing it would mean that recording the existence
 *       of an amendment broke the original's own signature — the record would
 *       appear tampered with by the very act of correcting it properly.</li>
 *   <li>{@code updated_at}. Set on every save, including the save that performs
 *       the signing, so including it would make the hash depend on a value
 *       written after it was computed.</li>
 *   <li>The soft-delete and retention columns. Ceasing to use a record, or
 *       calculating when it may be destroyed, says nothing about what the doctor
 *       found. Hashing them would make an archivist's action look like a change
 *       to clinical content.</li>
 *   <li>Diagnosis row ids. They are database identity, not clinical fact; the
 *       code, the name and the primary flag are what was diagnosed.</li>
 * </ul>
 *
 * <p>Timestamps are truncated to microseconds, which is what PostgreSQL keeps.
 * Hashing nanoseconds would produce a signature that stops verifying the moment
 * the record is read back from the database it was written to.
 *
 * <p>Once a single record has been signed in production this format is frozen.
 */
final class EncounterCanonicalForm {

    private static final String NULL_LENGTH = "-1:";
    static final String HASH_ALGORITHM = "SHA-256";

    private EncounterCanonicalForm() {
    }

    /** @return base64 SHA-256 over the canonical form */
    static String hash(Encounter encounter, List<EncounterDiagnosis> diagnoses) {
        StringBuilder canonical = new StringBuilder();

        append(canonical, encounter.getId());
        append(canonical, encounter.getPatientId());
        append(canonical, encounter.getDoctorId());
        append(canonical, encounter.getAppointmentId());
        append(canonical, encounter.getEncounteredAt() == null ? null
                : encounter.getEncounteredAt().truncatedTo(ChronoUnit.MICROS));
        append(canonical, encounter.getReason());
        append(canonical, encounter.getTreatmentPlan());
        append(canonical, encounter.getAdvice());
        append(canonical, encounter.getFollowUpDate());
        append(canonical, encounter.getReferralNote());

        // The schema version is part of what was signed: the same clinical data
        // means different things under different forms, so a signature over the
        // data alone would not pin down what it meant.
        append(canonical, encounter.getClinicalDataSchemaId());
        append(canonical, canonicalJson(encounter.getClinicalData()));

        // An amendment's own identity is signed, so that a correction cannot be
        // re-pointed at a different original or have its stated reason changed.
        append(canonical, encounter.getAmendsEncounterId());
        append(canonical, encounter.getAmendmentReason());

        // Diagnoses in a fixed order, independent of how the database returned
        // them. Sorted by code: a row order that varies would make the hash vary.
        append(canonical, String.valueOf(diagnoses.size()));
        diagnoses.stream()
                .sorted((a, b) -> a.getIcd10Code().compareTo(b.getIcd10Code()))
                .forEach(diagnosis -> {
                    append(canonical, diagnosis.getIcd10Code());
                    append(canonical, diagnosis.getIcd10Name());
                    append(canonical, diagnosis.isPrimary() ? "1" : "0");
                    append(canonical, diagnosis.getNote());
                });

        return sha256(canonical.toString());
    }

    /**
     * A stable string for the clinical data: keys sorted, nested maps and lists
     * handled recursively, every part length-prefixed.
     *
     * <p>Sorting matters because a {@code jsonb} column does not promise to
     * return keys in the order they were written, so the same data read back
     * twice can arrive in two different orders. Without sorting, a record would
     * fail to verify against its own signature for no reason at all.
     */
    private static String canonicalJson(Object value) {
        if (value == null) {
            return null;
        }

        StringBuilder canonical = new StringBuilder();
        appendJson(canonical, value);
        return canonical.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendJson(StringBuilder target, Object value) {
        if (value == null) {
            append(target, "n");
            append(target, null);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            append(target, "m");
            append(target, String.valueOf(map.size()));
            // TreeMap for the sort; keys in a JSON object are strings.
            new TreeMap<>((Map<String, Object>) map).forEach((key, nested) -> {
                append(target, key);
                appendJson(target, nested);
            });
            return;
        }
        if (value instanceof List<?> list) {
            // Lists keep their order, because order is meaningful in a list: the
            // sequence of lesion types a doctor entered is data, not an
            // implementation detail of how it was stored.
            append(target, "l");
            append(target, String.valueOf(list.size()));
            list.forEach(element -> appendJson(target, element));
            return;
        }
        if (value instanceof Boolean flag) {
            append(target, "b");
            append(target, flag ? "1" : "0");
            return;
        }
        if (value instanceof Number number) {
            append(target, "d");
            // Through BigDecimal so that 5, 5.0 and 5.00 do not hash differently
            // depending on whether Jackson handed back an Integer or a Double
            // after a round trip through jsonb.
            append(target, new java.math.BigDecimal(number.toString())
                    .stripTrailingZeros().toPlainString());
            return;
        }

        append(target, "s");
        append(target, value.toString());
    }

    /** Writes one part as {@code <length>:<value>}, or {@code -1:} for null. */
    private static void append(StringBuilder target, Object value) {
        if (value == null) {
            target.append(NULL_LENGTH);
            return;
        }
        String text = value.toString();
        target.append(text.length()).append(':').append(text);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return Base64.getEncoder().encodeToString(
                    digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec; unreachable on a valid JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
