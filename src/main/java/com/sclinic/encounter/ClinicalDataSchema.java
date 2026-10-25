package com.sclinic.encounter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One version of the clinical form for a specialty, as a JSON Schema.
 *
 * <p>Held in the database rather than on the classpath so that a record can be
 * re-validated against the exact schema it was written under, years later and
 * several application versions on. A schema shipped as a resource file is a
 * schema that changes when the jar changes, which would quietly redefine what a
 * historical record was supposed to contain.
 */
@Entity
@Table(name = "clinical_data_schema")
@Getter
@Setter
public class ClinicalDataSchema {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    /**
     * Which specialty's form this is.
     *
     * <p>A code, not a reference to the specialty table. These are versioned
     * documents that outlive organisational change, and a specialty row being
     * renamed or merged must not alter what an old record was validated against.
     */
    @Column(name = "specialty_code", nullable = false)
    private String specialtyCode;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> schema;

    /** Null until published. A draft schema must not validate real records. */
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
