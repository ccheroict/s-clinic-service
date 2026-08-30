package com.sclinic.facility;

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
import java.time.LocalDate;
import java.util.UUID;

/**
 * The clinic itself (co so kham chua benh).
 *
 * <p>Root identity record: supplies the facility code used to build prescription
 * codes, the interop code used to authenticate against the national
 * e-prescription system, and the e-invoice configuration.
 */
@Entity
@Table(name = "facility")
@Getter
@Setter
public class Facility {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Ma co so KCB issued by the health authority. */
    @Column(name = "kcb_code", nullable = false, unique = true)
    private String kcbCode;

    /** Ma lien thong co so, used to log in to the national e-prescription system. */
    @Column(name = "interop_code")
    private String interopCode;

    @Column(name = "tax_code")
    private String taxCode;

    private String address;
    private String phone;
    private String email;

    /** So giay phep hoat dong (Nghi dinh 96/2023). */
    @Column(name = "license_no")
    private String licenseNo;

    @Column(name = "license_issued_at")
    private LocalDate licenseIssuedAt;

    /** Nguoi chiu trach nhiem chuyen mon. */
    @Column(name = "technical_director")
    private String technicalDirector;

    // E-invoice configuration (provider: VNPT)

    @Column(name = "einvoice_template_code")
    private String einvoiceTemplateCode;

    @Column(name = "einvoice_serial")
    private String einvoiceSerial;

    @Column(name = "einvoice_unit_code")
    private String einvoiceUnitCode;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
