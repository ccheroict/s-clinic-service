package com.sclinic.patient;

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
 * Patient demographic + administrative record.
 * Sensitive fields (medicalHistory, allergies) are personal health data.
 */
@Entity
@Table(name = "patient")
@Getter
@Setter
public class Patient {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    private String code;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private LocalDate dob;

    /** M / F / U */
    private String sex;

    private String phone;
    private String address;

    @Column(name = "medical_history")
    private String medicalHistory;

    private String allergies;
    private String note;

    // V2 integration-readiness identifiers
    @Column(name = "national_id")
    private String nationalId;     // CCCD/CMND

    @Column(name = "insurance_no")
    private String insuranceNo;    // BHYT

    @Column(name = "tax_code")
    private String taxCode;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
