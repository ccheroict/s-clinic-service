package com.sclinic.audit;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * The next slice of chained entries after {@code afterId}, in chain order.
     *
     * <p>Ordering by id is sound because writers hold the {@link AuditChainHead}
     * row lock across the insert and the commit, so ids are handed out in the same
     * order the rows become visible. A sequence generator with pooled allocation
     * would break that permanently; {@code GenerationType.IDENTITY} allocates the
     * id in the insert itself.
     *
     * <p>Keyset paging rather than offset paging: verification walks the whole
     * trail, and {@code OFFSET n} re-scans the rows it skips, so an offset walk
     * costs quadratic time in the length of the trail.
     */
    List<AuditLog> findByEntryHashIsNotNullAndIdGreaterThanOrderByIdAsc(long afterId, Limit limit);

    /** Entries written before the chain existed (before migration V7). */
    long countByEntryHashIsNull();
}
