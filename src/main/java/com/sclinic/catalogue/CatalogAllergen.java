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

/**
 * A substance a patient can react to.
 *
 * <p>Coded rather than written out because the allergy check that matters is the
 * one that fires regardless of how the substance was spelled. "Penicillin",
 * "penicilin" and "PNC" are one substance to a person and three strings to a
 * database.
 */
@Entity
@Table(name = "catalog_allergen")
@Getter
@Setter
public class CatalogAllergen {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    /** DRUG / FOOD / ENVIRONMENT / OTHER */
    @Column(nullable = false)
    private String category;

    /**
     * The class this substance belongs to, so an allergy to one penicillin can
     * warn about the group. Self-referencing because a drug class is itself
     * something a patient reacts to, not a different kind of thing.
     */
    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
