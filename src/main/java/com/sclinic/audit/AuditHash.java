package com.sclinic.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The link in the audit hash chain: SHA-256 over an entry plus the hash of the
 * entry before it.
 *
 * <p>Pure and stateless on purpose, so the exact bytes that get hashed can be
 * pinned down by tests.
 *
 * <p>The canonical form is <b>length-prefixed</b> rather than
 * separator-delimited: each part is written as its character length, a colon,
 * then the part itself. A delimiter can be imitated by field content, so
 * {@code ip="a|b", userAgent=null} and {@code ip="a", userAgent="b"} would hash
 * identically and one could be rewritten as the other without breaking the
 * chain. With a length prefix the decomposition is unique no matter what the
 * fields contain, so no combination of values can imitate a different set of
 * values. Null is written as a length of {@code -1}, which no real string can
 * produce, so a null field is never confused with an empty one.
 *
 * <p>Timestamps are truncated to microseconds, matching what PostgreSQL stores.
 * Hashing nanoseconds would produce a chain that never verifies after a round
 * trip through the database.
 *
 * <p>Once entries exist in production this format is frozen: changing it makes
 * every previously written entry fail verification.
 */
final class AuditHash {

    private static final String NULL_LENGTH = "-1:";

    /** Type tags for detail values, written as parts of their own. */
    private static final String TAG_TEXT = "t";
    private static final String TAG_MAP = "m";

    private AuditHash() {
    }

    /**
     * @param prevHash hash of the preceding entry, or null for the first one
     * @return lowercase hex SHA-256 of the canonical form
     */
    static String of(String prevHash, UUID staffId, String action, String entityType, UUID entityId,
                     Map<String, Object> detail, String ip, String userAgent, UUID sessionId,
                     Instant createdAt) {

        StringBuilder canonical = new StringBuilder();
        append(canonical, prevHash);
        append(canonical, staffId);
        append(canonical, action);
        append(canonical, entityType);
        append(canonical, entityId);
        append(canonical, canonicalDetail(detail));
        append(canonical, ip);
        append(canonical, userAgent);
        append(canonical, sessionId);
        append(canonical, createdAt);

        return sha256(canonical.toString());
    }

    /** Recomputes the hash a stored entry should have, for verification. */
    static String of(String prevHash, AuditLog entry) {
        return of(prevHash, entry.getStaffId(), entry.getAction(), entry.getEntityType(),
                entry.getEntityId(), entry.getDetail(), entry.getIp(), entry.getUserAgent(),
                entry.getSessionId(), entry.getCreatedAt());
    }

    /**
     * A stable string for the detail map: keys sorted, nested maps handled
     * recursively, every part length-prefixed like the top-level fields.
     *
     * <p>Sorting matters because a {@code jsonb} column read back does not
     * promise the insertion order it was written with.
     *
     * @return null when there is nothing recorded, so "no detail" stays
     *         distinguishable from any actual detail
     */
    private static String canonicalDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }

        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, Object> entry : new TreeMap<>(detail).entrySet()) {
            append(canonical, entry.getKey());
            appendValue(canonical, entry.getValue());
        }
        return canonical.toString();
    }

    /**
     * Writes a detail value as a type tag followed by the value itself, each
     * length-prefixed in its own right.
     *
     * <p>The tag has to be a separate part rather than a prefix inside the value:
     * a tag glued onto the front of the text would just be more text, and a plain
     * string could be written to imitate it. As two parts it cannot be, because a
     * string value always produces exactly one part after its tag.
     */
    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder target, Object value) {
        if (value instanceof Map<?, ?> nested) {
            append(target, TAG_MAP);
            append(target, canonicalDetail((Map<String, Object>) nested));
            return;
        }
        append(target, TAG_TEXT);
        append(target, value == null ? null : value.toString());
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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec; unreachable on a valid JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
