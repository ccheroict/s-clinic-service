package com.sclinic.audit;

import com.sclinic.crypto.CryptoKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The link in the audit hash chain: HMAC-SHA256 over an entry plus the hash of
 * the entry before it.
 *
 * <p><b>Keyed, and the key is the point.</b> This was a bare SHA-256 until the
 * review of task 1.9c. Every field it covers is readable from {@code audit_log},
 * and the expected head hash sits in {@code audit_chain_head} in the same
 * database, so anyone able to write both tables could alter history and recompute
 * a chain that agreed with itself end to end. An unkeyed digest detects
 * carelessness — a bug, a careless migration, an operator editing a row — but not
 * intent. With an HMAC the recomputation needs {@code sclinic.audit.chain-key},
 * which lives in the process environment and not in PostgreSQL, so database
 * access alone is no longer enough to forge a consistent trail.
 *
 * <p>Still not defended against: whoever holds the database <em>and</em> this
 * process's environment. Closing that needs the head hash published somewhere
 * append-only off the box, which is infrastructure rather than code.
 *
 * <p>A bean rather than static methods, which is what it was. The key has to come
 * from configuration and a static method has nowhere to read it from. The
 * canonical-form logic below stays pure, so the exact bytes covered can still be
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
 * every previously written entry fail verification. The move from SHA-256 to
 * HMAC was made before any production deployment, which is the only point at
 * which it could be made at all — afterwards it would have required either
 * rewriting every stored hash, which is exactly the capability the chain exists
 * to deny, or keeping both algorithms and a flag saying which applied to each
 * entry, which hands an attacker the choice.
 */
@Component
public class AuditHash {

    private static final String NULL_LENGTH = "-1:";
    private static final String MAC_ALGORITHM = "HmacSHA256";

    /** Type tags for detail values, written as parts of their own. */
    private static final String TAG_TEXT = "t";
    private static final String TAG_MAP = "m";

    private final byte[] key;

    // Explicit, because the byte[] constructor below is also a candidate and two
    // constructors with no marked one makes Spring look for a default instead.
    @Autowired
    public AuditHash(AuditChainProperties properties) {
        this(CryptoKeys.decode(properties.chainKey(), "sclinic.audit.chain-key",
                "The audit trail is chained with a keyed hash so that database access alone "
                        + "cannot rewrite it"));
    }

    /** For tests, which need a fixed key to pin the canonical form against. */
    AuditHash(byte[] key) {
        this.key = key.clone();
    }

    /**
     * @param prevHash hash of the preceding entry, or null for the first one
     * @return lowercase hex HMAC-SHA256 of the canonical form
     */
    public String of(String prevHash, UUID staffId, String action, String entityType, UUID entityId,
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

        return mac(canonical.toString());
    }

    /** Recomputes the hash a stored entry should have, for verification. */
    public String of(String prevHash, AuditLog entry) {
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

    private String mac(String input) {
        try {
            Mac hmac = Mac.getInstance(MAC_ALGORITHM);
            hmac.init(new SecretKeySpec(key, MAC_ALGORITHM));
            return HexFormat.of()
                    .formatHex(hmac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is mandated by the JCA spec; unreachable on a valid JVM.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
