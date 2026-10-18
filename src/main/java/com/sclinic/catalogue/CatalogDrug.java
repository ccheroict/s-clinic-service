package com.sclinic.catalogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** A prescribable drug. */
@Entity
@Table(name = "catalog_drug")
@Getter
@Setter
public class CatalogDrug {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    /** The clinic's own code. */
    @Column(nullable = false, unique = true)
    private String code;

    /**
     * Code in the Ministry of Health drug list, for the national e-prescription
     * system. Separate from {@link #code} because a drug may not have one yet,
     * and a prescription needs whichever exists.
     */
    @Column(name = "national_code")
    private String nationalCode;

    /**
     * International non-proprietary name: 'Amoxicillin'. This is the field an
     * allergy check means — the brand name varies by manufacturer, the substance
     * does not.
     */
    @Column(name = "inn_name", nullable = false)
    private String innName;

    /** 'Augmentin'. What is on the box, and what the patient will call it. */
    @Column(name = "brand_name")
    private String brandName;

    private String strength;

    @Column(name = "dosage_form")
    private String dosageForm;

    private String route;

    @Column(nullable = false)
    private String unit = "vien";

    /**
     * NARCOTIC / PSYCHOTROPIC / PRECURSOR, or null for an ordinary drug.
     *
     * <p>On the drug rather than remembered by the prescriber: the extra duties
     * that come with prescribing these are not something to rely on anyone
     * recognising from the name.
     */
    @Column(name = "control_class")
    private String controlClass;

    /**
     * What this drug contains, for the allergy check at prescribing time.
     *
     * <p>Many-to-many because a combination product contains several substances
     * and a substance appears in many products.
     */
    @ManyToMany
    @JoinTable(
            name = "catalog_drug_allergen",
            joinColumns = @JoinColumn(name = "drug_id"),
            inverseJoinColumns = @JoinColumn(name = "allergen_id"))
    private Set<CatalogAllergen> allergens = new LinkedHashSet<>();

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Whether prescribing this carries the special-control duties. */
    public boolean isSpecialControl() {
        return controlClass != null;
    }
}
