package com.sclinic.patient;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Page<Patient> findByFullNameContainingIgnoreCaseOrPhoneContaining(
            String fullName, String phone, Pageable pageable);
}
