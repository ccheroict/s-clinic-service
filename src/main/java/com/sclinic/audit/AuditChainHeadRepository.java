package com.sclinic.audit;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, Short> {

    /**
     * Reads the chain head under a write lock, so only one audit entry at a time
     * can append to the chain.
     *
     * <p>{@code SELECT ... FOR UPDATE}: the lock is held until the writing
     * transaction commits, which is exactly as long as the new entry needs to
     * become visible to the next writer.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from AuditChainHead h where h.id = 1")
    Optional<AuditChainHead> lockHead();

    /** Reads the head without locking, for verification. */
    @Query("select h from AuditChainHead h where h.id = 1")
    Optional<AuditChainHead> peekHead();
}
