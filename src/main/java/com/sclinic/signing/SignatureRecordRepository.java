package com.sclinic.signing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SignatureRecordRepository extends JpaRepository<SignatureRecord, UUID> {

    /**
     * The signature on one record, newest first.
     *
     * <p>A list rather than one, although a record is signed once: the table is
     * append-only, so a re-signature after an amendment adds a row, and reading
     * the newest is a decision the caller should make explicitly.
     */
    List<SignatureRecord> findByEntityTypeAndEntityIdOrderBySignedAtDesc(
            String entityType, UUID entityId);

    Optional<SignatureRecord> findFirstByEntityTypeAndEntityIdOrderBySignedAtDesc(
            String entityType, UUID entityId);

    boolean existsByEntityTypeAndEntityId(String entityType, UUID entityId);
}
