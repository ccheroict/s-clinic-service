package com.sclinic.encounter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * One diagnosis on one encounter.
 *
 * <p>Carries both a reference to the ICD-10 catalogue and a copy of the code and
 * name as they stood when it was recorded. The reference is how current
 * catalogue data is reached; the copy is what makes the historical record
 * truthful. If the Ministry retires a code or the Vietnamese wording of one is
 * corrected, a record signed years earlier must keep saying what the doctor
 * signed — the same reason a consent record freezes its purpose.
 */
@Entity
@Table(name = "encounter_diagnosis")
@Getter
@Setter
public class EncounterDiagnosis {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "encounter_id", nullable = false)
    private UUID encounterId;

    @Column(name = "icd10_id", nullable = false)
    private UUID icd10Id;

    @Column(name = "icd10_code", nullable = false)
    private String icd10Code;

    @Column(name = "icd10_name", nullable = false)
    private String icd10Name;

    /**
     * The main diagnosis for this encounter.
     *
     * <p>A partial unique index allows at most one per encounter. Exactly one is
     * required at signing rather than at every insert, because a draft being
     * typed legitimately passes through having none — and a constraint forbidding
     * that would make the first diagnosis entered automatically the primary one,
     * with a two-step dance to correct it.
     */
    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    private String note;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
