package com.sclinic.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * The single row that tracks the end of the audit chain.
 *
 * <p>Serves as both the write lock that keeps the chain from forking and an
 * independent record of where the chain should end. See migration V7 for the
 * reasoning; {@link AuditService} takes the lock and {@link AuditChainVerifier}
 * checks the recorded head against the real one.
 */
@Entity
@Table(name = "audit_chain_head")
@Getter
@Setter
public class AuditChainHead {

    /** Always 1: the table is a single row by construction. */
    public static final short SINGLETON_ID = 1;

    @Id
    private Short id = SINGLETON_ID;

    /** Hash of the last chained entry, or null before the first one. */
    @Column(name = "head_hash")
    private String headHash;

    /** How many chained entries exist. Entries predating V7 are not counted. */
    @Column(name = "entry_count", nullable = false)
    private long entryCount;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
