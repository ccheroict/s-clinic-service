package com.sclinic.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit trail entry: who did what to which record, from where, and
 * when.
 *
 * <p>Deliberately does NOT store record values (avoids duplicating sensitive
 * health data); {@code detail} holds only field names and short codes. See
 * {@link AuditDetail}.
 *
 * <p>Every row links to the one before it through {@code prevHash} /
 * {@code entryHash}, so an altered or removed row breaks the chain. Nothing
 * updates an existing row: the database rejects UPDATE and DELETE outright
 * (V7), which is also why {@code createdAt} is set by the application before
 * insert rather than filled in afterwards.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // bigserial
    private Long id;

    @Column(name = "staff_id")
    private UUID staffId;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detail;

    // ---------- Request context (V7) ----------

    @Column(name = "ip")
    private String ip;

    @Column(name = "user_agent")
    private String userAgent;

    /** Session that performed the action; no FK, see V7 for why. */
    @Column(name = "session_id")
    private UUID sessionId;

    // ---------- Hash chain (V7) ----------

    /** Hash of the preceding entry. Null for the first entry of the chain. */
    @Column(name = "prev_hash", updatable = false)
    private String prevHash;

    @Column(name = "entry_hash", updatable = false)
    private String entryHash;

    /**
     * Set explicitly before insert, not by {@code @CreationTimestamp}: the value
     * is part of what {@code entryHash} covers, so it has to be known before the
     * row is written. Truncated to microseconds to match what PostgreSQL keeps.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
