package com.sclinic.bootstrap;

import com.sclinic.facility.Facility;
import com.sclinic.facility.FacilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the facility record on first startup when none exists, so downstream
 * modules (prescription codes, e-invoice, printed documents) always have an
 * identity to work with.
 *
 * <p>Idempotent: does nothing once any facility row exists, so restarts never
 * create a duplicate and never overwrite values an admin edited at runtime.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FacilitySeeder implements CommandLineRunner {

    private final FacilityRepository facilityRepository;

    @Value("${sclinic.facility.name:Phong kham da lieu S-Clinic}")
    private String name;

    @Value("${sclinic.facility.kcb-code:PENDING-KCB-CODE}")
    private String kcbCode;

    @Value("${sclinic.facility.interop-code:}")
    private String interopCode;

    @Value("${sclinic.facility.tax-code:}")
    private String taxCode;

    @Value("${sclinic.facility.address:}")
    private String address;

    @Value("${sclinic.facility.phone:}")
    private String phone;

    @Value("${sclinic.facility.email:}")
    private String email;

    @Value("${sclinic.facility.license-no:}")
    private String licenseNo;

    @Value("${sclinic.facility.technical-director:}")
    private String technicalDirector;

    @Value("${sclinic.facility.einvoice-template-code:}")
    private String einvoiceTemplateCode;

    @Value("${sclinic.facility.einvoice-serial:}")
    private String einvoiceSerial;

    @Value("${sclinic.facility.einvoice-unit-code:}")
    private String einvoiceUnitCode;

    @Override
    public void run(String... args) {
        if (facilityRepository.count() > 0) {
            return;
        }

        Facility facility = new Facility();
        facility.setName(name);
        facility.setKcbCode(kcbCode);
        facility.setInteropCode(blankToNull(interopCode));
        facility.setTaxCode(blankToNull(taxCode));
        facility.setAddress(blankToNull(address));
        facility.setPhone(blankToNull(phone));
        facility.setEmail(blankToNull(email));
        facility.setLicenseNo(blankToNull(licenseNo));
        facility.setTechnicalDirector(blankToNull(technicalDirector));
        facility.setEinvoiceTemplateCode(blankToNull(einvoiceTemplateCode));
        facility.setEinvoiceSerial(blankToNull(einvoiceSerial));
        facility.setEinvoiceUnitCode(blankToNull(einvoiceUnitCode));
        facility.setActive(true);
        facilityRepository.save(facility);

        log.warn("Created initial facility record '{}' with KCB code '{}'. "
                        + "Update it via PUT /api/facility before issuing prescriptions or invoices.",
                name, kcbCode);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
