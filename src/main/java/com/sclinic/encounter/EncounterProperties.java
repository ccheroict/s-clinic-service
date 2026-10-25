package com.sclinic.encounter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param defaultSpecialtyCode which clinical form a new encounter uses when the
 *        caller does not name one.
 *        <p>Defaults to DERMATOLOGY, which is what this clinic is. Configurable
 *        rather than hardcoded because the whole reason the clinical data is
 *        schema-driven is that the next clinic to run this is not a dermatology
 *        clinic — and a constant buried in a service is the thing that makes a
 *        "configurable" design turn out not to be.
 */
@ConfigurationProperties(prefix = "sclinic.encounter")
public record EncounterProperties(String defaultSpecialtyCode) {

    public EncounterProperties {
        if (defaultSpecialtyCode == null || defaultSpecialtyCode.isBlank()) {
            defaultSpecialtyCode = "DERMATOLOGY";
        }
    }
}
