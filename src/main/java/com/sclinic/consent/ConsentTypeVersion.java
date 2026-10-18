package com.sclinic.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One version of the wording a patient reads before consenting.
 *
 * <p>Versioned because wording changes and a consent record has to point at what
 * the patient actually read. A record pointing at "the consent form" points at
 * whatever the form says today.
 */
@Entity
@Table(name = "consent_type_version")
@Getter
@Setter
public class ConsentTypeVersion {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "type_id", nullable = false)
    private UUID typeId;

    @Column(nullable = false)
    private Integer version;

    /**
     * The text the patient reads, in full.
     *
     * <p>Not a reference to a document: a URL is not evidence, and a file that
     * can be replaced in place is worse than no evidence at all.
     */
    @Column(nullable = false)
    private String content;

    /**
     * The statement that the data processed is sensitive personal data.
     *
     * <p>Requirement 11.4. Its own column rather than trusted to be somewhere
     * inside {@link #content}, so it can be required for sensitive types and so
     * a reviewer can find it without reading the whole form.
     */
    @Column(name = "sensitivity_notice")
    private String sensitivityNotice;

    /**
     * The purpose of processing, in the words the patient sees.
     *
     * <p>NĐ 13/2023 requires consent to be specific as to purpose. Consent to
     * "data processing" in general is not consent.
     */
    @Column(nullable = false)
    private String purpose;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /**
     * Null until published.
     *
     * <p>An unpublished version is a draft, and collecting consent against a
     * draft is collecting it against wording that may still change.
     */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "retired_at")
    private Instant retiredAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public boolean isPublished() {
        return publishedAt != null && retiredAt == null;
    }
}
