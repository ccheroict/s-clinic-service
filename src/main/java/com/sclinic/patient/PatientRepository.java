package com.sclinic.patient;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Withdrawn records are excluded by naming them in each query rather than by a
 * global Hibernate filter.
 *
 * <p>A filter applied behind the scenes reads as if every query is unfiltered,
 * which is the wrong default for the one case that matters: a query that must see
 * withdrawn records — retention management, or resolving the patient on an old
 * appointment — would silently return nothing, and the mistake would look like
 * missing data rather than a filtered query. Spelling it out in the method name
 * means the caller has to say which behaviour they want.
 */
public interface PatientRepository extends JpaRepository<Patient, UUID> {

    /** For the business API: a withdrawn record is gone as far as it is concerned. */
    Optional<Patient> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * Exact lookup by national identity number.
     *
     * <p>Takes the blind index, not the number: the encrypted column uses a fresh
     * IV per write, so comparing ciphertext can never match. See
     * {@code BlindIndex}.
     */
    Optional<Patient> findByNationalIdHashAndDeletedAtIsNull(String nationalIdHash);

    Page<Patient> findByDeletedAtIsNull(Pageable pageable);

    /**
     * Search across name and phone, excluding withdrawn records.
     *
     * <p>Written out rather than derived from the method name: the derived form
     * for two alternative terms plus the withdrawal predicate needs the predicate
     * repeated in each branch, which is how a query ends up matching withdrawn
     * records through whichever branch the author forgot to guard.
     */
    @Query("""
            SELECT p FROM Patient p
             WHERE p.deletedAt IS NULL
               AND (LOWER(p.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR p.phone LIKE CONCAT('%', :keyword, '%'))
            """)
    Page<Patient> searchActive(@Param("keyword") String keyword, Pageable pageable);

    /**
     * The patients one doctor may list: those they have an appointment with, plus
     * any they currently hold a break-glass grant over.
     *
     * <p>Requirement 8.1. Written as one query rather than filtering a full page
     * afterwards, because post-filtering a paged result returns short pages and a
     * wrong total — and a patient list that silently drops entries is worse than
     * one that refuses.
     */
    @Query("""
            SELECT p FROM Patient p
             WHERE p.deletedAt IS NULL
               AND (LOWER(p.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR p.phone LIKE CONCAT('%', :keyword, '%'))
               AND (EXISTS (SELECT 1 FROM Appointment a
                             WHERE a.patientId = p.id AND a.doctorId = :doctorId)
                    OR p.id IN :grantedPatientIds)
            """)
    Page<Patient> searchActiveForDoctor(@Param("keyword") String keyword,
                                        @Param("doctorId") UUID doctorId,
                                        @Param("grantedPatientIds") Collection<UUID> grantedPatientIds,
                                        Pageable pageable);

    /**
     * The unfiltered version of {@link #searchActiveForDoctor}.
     *
     * <p>A separate method rather than passing a null keyword into that one:
     * a null parameter has no inferred SQL type, so Hibernate binds it as
     * {@code bytea} and {@code lower(?)} fails outright. Splitting the two cases
     * is also what the non-doctor path already does.
     */
    @Query("""
            SELECT p FROM Patient p
             WHERE p.deletedAt IS NULL
               AND (EXISTS (SELECT 1 FROM Appointment a
                             WHERE a.patientId = p.id AND a.doctorId = :doctorId)
                    OR p.id IN :grantedPatientIds)
            """)
    Page<Patient> findActiveForDoctor(@Param("doctorId") UUID doctorId,
                                      @Param("grantedPatientIds") Collection<UUID> grantedPatientIds,
                                      Pageable pageable);

    /**
     * Records whose retention period has run out and that are not on legal hold.
     *
     * <p>A null {@code retentionUntil} fails the comparison, so a record with no
     * computed retention date is never eligible. That is what makes an
     * unconfigured retention policy safe: it keeps everything.
     */
    List<Patient> findByDeletedAtIsNotNullAndLegalHoldFalseAndRetentionUntilLessThan(
            LocalDate before);
}
