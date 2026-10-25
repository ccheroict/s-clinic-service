package com.sclinic.encounter;

import lombok.Getter;

import java.util.List;

/**
 * The clinical data did not match the schema it was recorded against.
 *
 * <p>Carries every failure rather than the first one. A doctor correcting a form
 * should see the whole list; discovering the problems one submission at a time is
 * how a clinician decides the form is broken and writes the finding into the free
 * text field instead, which is exactly the outcome structured data exists to
 * avoid.
 */
@Getter
public class ClinicalDataInvalidException extends RuntimeException {

    private final String specialtyCode;
    private final int schemaVersion;
    private final List<String> failures;

    public ClinicalDataInvalidException(String specialtyCode, int schemaVersion,
                                        List<String> failures) {
        super("Dữ liệu lâm sàng không hợp lệ theo schema " + specialtyCode
                + " bản " + schemaVersion + ": " + String.join("; ", failures));
        this.specialtyCode = specialtyCode;
        this.schemaVersion = schemaVersion;
        this.failures = List.copyOf(failures);
    }
}
