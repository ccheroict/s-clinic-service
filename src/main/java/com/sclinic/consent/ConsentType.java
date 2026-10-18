package com.sclinic.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A kind of consent the clinic collects.
 *
 * <p>Seeded by V14 from the list in requirement 11.1. The patient-facing wording
 * is not here: it is versioned, and this is not.
 */
@Entity
@Table(name = "consent_type")
@Getter
@Setter
public class ConsentType {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    /**
     * Whether this consent covers sensitive personal data under NĐ 13/2023.
     *
     * <p>Decides what the wording must say: a version of a sensitive type cannot
     * be published without a notice telling the patient so. Enforced by a
     * database trigger as well as by the service, because version rows also
     * arrive from migrations and from whatever an administrator runs by hand.
     */
    @Column(name = "is_sensitive", nullable = false)
    private boolean sensitive;

    /**
     * Whether care may proceed without it.
     *
     * <p>Data rather than a rule in code. Treatment consent gates treatment;
     * marketing consent gates marketing and must never block care, and a
     * condition expressed in code is one that grows an exception later.
     */
    @Column(name = "blocks_care", nullable = false)
    private boolean blocksCare;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public ConsentTypeCode typeCode() {
        return ConsentTypeCode.valueOf(code);
    }
}
