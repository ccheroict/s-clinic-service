package com.sclinic.signing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Which signing provider the clinic uses, and how to reach it.
 *
 * @param provider which implementation to use. Unset means none: the application
 *        starts, every other feature works, and an attempt to sign is refused
 *        with an explanation. That is deliberate — a clinic mid-setup should be
 *        able to enter patients and write drafts before its certificates arrive,
 *        and the one thing that must not happen is signing appearing to work
 *        when nothing legally signed anything
 * @param baseUrl the provider's API root
 * @param clientId credentials the clinic is issued by the provider
 * @param clientSecret as above. Never logged, never returned by any endpoint
 * @param confirmationTimeout how long to wait for the signer to approve on their
 *        phone. Remote signing is a push notification and a PIN, so this is tens
 *        of seconds by nature rather than the usual HTTP timeout
 * @param pollInterval how often to ask the provider whether the signer has
 *        approved yet
 */
@ConfigurationProperties(prefix = "sclinic.signing")
public record SigningProperties(
        String provider,
        String baseUrl,
        String clientId,
        String clientSecret,
        Duration confirmationTimeout,
        Duration pollInterval
) {

    public SigningProperties {
        if (confirmationTimeout == null) {
            confirmationTimeout = Duration.ofMinutes(3);
        }
        if (pollInterval == null) {
            pollInterval = Duration.ofSeconds(3);
        }
    }

    public boolean isConfigured() {
        return provider != null && !provider.isBlank();
    }
}
