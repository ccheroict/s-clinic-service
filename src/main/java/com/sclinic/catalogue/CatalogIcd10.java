package com.sclinic.catalogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** A diagnosis code. Loaded from the published ICD-10 list, never typed in. */
@Entity
@Table(name = "catalog_icd10")
@Getter
@Setter
public class CatalogIcd10 {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    /** 'L20.9'. Text, because ICD-10 codes contain letters and dots. */
    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "name_en")
    private String nameEn;

    /** 'XII' for skin diseases, so a dermatology clinic can narrow the picker. */
    private String chapter;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
