package com.sclinic.access;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param breakGlassDuration how long a break-glass grant lasts. Long enough to
 *                           finish treating the patient, short enough that it
 *                           does not become a standing permission
 */
@ConfigurationProperties(prefix = "sclinic.access")
public record AccessProperties(
        Duration breakGlassDuration
) {

    public AccessProperties {
        if (breakGlassDuration == null || breakGlassDuration.isZero()
                || breakGlassDuration.isNegative()) {
            breakGlassDuration = Duration.ofHours(8);
        }
    }
}
