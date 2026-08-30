package com.sclinic.staff;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StaffRepository extends JpaRepository<Staff, UUID> {

    Optional<Staff> findByUsernameAndActiveTrue(String username);

    /**
     * Reads a staff row under a write lock, for the failed-attempt counter.
     *
     * <p>Without the lock the counter is a read-modify-write: parallel wrong
     * password attempts all read the same value and all write the same value plus
     * one, so twenty simultaneous guesses consume one attempt instead of twenty.
     * The lockout would still engage eventually, but the number of guesses it
     * lets through would scale with how much concurrency the attacker can muster
     * rather than being capped at the configured limit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Staff s where s.id = :id")
    Optional<Staff> findByIdForUpdate(@Param("id") UUID id);
}
