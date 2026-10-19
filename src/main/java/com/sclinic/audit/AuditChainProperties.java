package com.sclinic.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The key that makes the audit chain evidence rather than a checksum.
 *
 * @param chainKey base64-encoded HMAC key for the audit hash chain.
 *                 <p>Its value is that it lives <em>outside PostgreSQL</em>.
 *                 Before this, each link was a bare SHA-256 over fields that are
 *                 all readable from the table, with the expected head hash in
 *                 another row of the same database — so anyone who could write
 *                 both could edit history and recompute a chain that agreed with
 *                 itself. The chain detected carelessness, not intent.
 *                 <p>Deliberately separate from {@code sclinic.crypto.key} and
 *                 {@code sclinic.crypto.index-key}. Those protect patient
 *                 identifiers; this one protects the record of who looked at
 *                 them. Sharing a key would mean one compromise both exposed the
 *                 identifiers and let the evidence of that exposure be rewritten.
 *                 <p>What it still does not defend against: whoever holds both
 *                 the database and this process's environment. For that the head
 *                 hash has to be published somewhere append-only off the box,
 *                 which is infrastructure rather than code.
 */
@ConfigurationProperties(prefix = "sclinic.audit")
public record AuditChainProperties(String chainKey) {
}
