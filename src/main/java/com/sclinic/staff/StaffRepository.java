package com.sclinic.staff;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StaffRepository extends JpaRepository<Staff, UUID> {

    Optional<Staff> findByUsernameAndActiveTrue(String username);

    /**
     * Claims a TOTP time step for an account, if it has not been used already.
     *
     * <p>Conditional, and the condition is the whole point: whoever gets the row
     * count wins. Two requests presenting the same code at the same moment would
     * both read the same {@code totpLastStep} and both conclude the code was
     * unused, so an unconditional update would let a single code authenticate
     * twice — which is the replay this exists to stop. Same shape as
     * {@code StaffBackupCodeRepository.spend}, for the same reason.
     *
     * <p>A conditional update rather than a pessimistic read lock because there
     * is nothing to read: the new value does not depend on the old one, only on
     * whether the old one was lower.
     *
     * @return 1 if the step was claimed, 0 if it was already used
     */
    @Modifying
    @Query("""
            update Staff s set s.totpLastStep = :step
            where s.id = :id and (s.totpLastStep is null or s.totpLastStep < :step)
            """)
    int advanceTotpStep(@Param("id") UUID id, @Param("step") long step);

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
