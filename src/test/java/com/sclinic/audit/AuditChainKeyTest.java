package com.sclinic.audit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the key buys the audit chain.
 *
 * <p>The chain was an unkeyed SHA-256 until the review of task 1.9c. Every field
 * it covered was readable from {@code audit_log} and the expected head hash sat
 * in {@code audit_chain_head} in the same database, so anyone who could write
 * both could edit history and recompute a chain that agreed with itself. It
 * detected carelessness, not intent.
 *
 * <p>These tests are about the one property that changed: recomputing the chain
 * now takes something the database does not hold.
 */
class AuditChainKeyTest {

    private static final UUID STAFF = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTITY = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SESSION = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant AT = Instant.parse("2026-10-19T03:00:00.123456Z");

    private static AuditHash withKey(String key) {
        return new AuditHash(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String hashWith(AuditHash auditHash) {
        return auditHash.of("prev", STAFF, "VIEW", "patient", ENTITY,
                Map.of("field", "changed"), "10.0.0.5", "JUnit", SESSION, AT);
    }

    @Test
    void theSameEntryUnderADifferentKeyGivesADifferentHash() {
        String underOurKey = hashWith(withKey("the-clinic-key"));
        String underAnotherKey = hashWith(withKey("an-attackers-guess"));

        // The whole point. Someone holding the table contents can reproduce the
        // canonical form exactly — every field is right there — and still cannot
        // produce the hash that goes with it.
        assertThat(underOurKey).isNotEqualTo(underAnotherKey);
    }

    @Test
    void theSameEntryUnderTheSameKeyGivesTheSameHash() {
        // Verification has to be able to recompute what was written, or the chain
        // would fail against itself.
        assertThat(hashWith(withKey("stable"))).isEqualTo(hashWith(withKey("stable")));
    }

    @Test
    void keysDifferingInOneBitProduceUnrelatedHashes() {
        String a = hashWith(withKey("key-a"));
        String b = hashWith(withKey("key-b"));

        assertThat(a).isNotEqualTo(b);
        // Not a strong statement about the MAC, which is HMAC-SHA256 and needs no
        // defending here. It is a guard against a refactor that appends the key
        // to the message instead of keying the MAC, which would leave most of the
        // output identical.
        assertThat(commonPrefixLength(a, b)).isLessThan(8);
    }

    @Test
    void theHashIsStillHexOfTheExpectedWidth() {
        // HMAC-SHA256, like SHA-256 before it, is 32 bytes. The column holds
        // 64 hex characters and a wider digest would be silently truncated by
        // the database rather than rejected.
        assertThat(hashWith(withKey("any"))).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void theApplicationRefusesToStartWithoutAKey() {
        // No default and no fallback, exactly as with the data encryption keys. A
        // default key in the repository would make the chain decorative, and
        // falling back to an unkeyed digest would leave the system looking
        // tamper-evident while being recomputable by anyone with the database.
        assertThatThrownBy(() -> new AuditHash(new AuditChainProperties(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sclinic.audit.chain-key");

        assertThatThrownBy(() -> new AuditHash(new AuditChainProperties("   ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sclinic.audit.chain-key");
    }

    @Test
    void aShortKeyIsRefusedRatherThanAccepted() {
        // Shares the length floor with the other keys. A two-byte HMAC key is
        // worse than no key, because it looks like protection.
        assertThatThrownBy(() -> new AuditHash(new AuditChainProperties("YWI=")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anInvalidBase64KeyIsRefusedAtStartup() {
        assertThatThrownBy(() -> new AuditHash(new AuditChainProperties("not base64 !!!")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mutatingTheKeyArrayAfterConstructionDoesNotChangeTheHash() {
        byte[] key = "mutable-key-source".getBytes(StandardCharsets.UTF_8);
        AuditHash auditHash = new AuditHash(key);
        String before = hashWith(auditHash);

        java.util.Arrays.fill(key, (byte) 0);

        // The constructor copies. Otherwise a caller clearing its own key buffer
        // — which is the responsible thing for a caller to do — would silently
        // change every hash written afterwards, and the chain would break with no
        // edit to any row.
        assertThat(hashWith(auditHash)).isEqualTo(before);
    }

    private static int commonPrefixLength(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }
}
