package com.sclinic.signing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Chooses the signing provider from configuration.
 *
 * <p>An explicit {@code if} rather than conditional annotations on the
 * implementations. The first attempt used {@code @ConditionalOnMissingBean} on a
 * scanned {@code @Component} to make the unconfigured provider a fallback, which
 * does not work: that annotation is for {@code @Bean} methods in
 * auto-configuration, where the evaluation order is defined, and on a component
 * it simply excluded the bean — leaving the application with no provider at all
 * and a context that failed to start.
 *
 * <p>Reading the property here and returning one or the other is longer by three
 * lines and says exactly what happens. For a decision this consequential — which
 * implementation signs medical records — that is the right trade.
 */
@Configuration
@Slf4j
public class SigningConfig {

    @Bean
    public DigitalSignatureProvider digitalSignatureProvider(SigningProperties properties,
                                                             Clock clock) {
        if (VnptSmartCaAdapter.PROVIDER_CODE.equalsIgnoreCase(properties.provider())) {
            log.info("Digital signature provider: VNPT SmartCA at {}", properties.baseUrl());
            return new VnptSmartCaAdapter(properties, clock);
        }

        if (properties.isConfigured()) {
            // A name that matches no implementation. Refused rather than
            // silently falling back, because a clinic that configured a provider
            // believes it has one, and quietly giving them the version that
            // refuses to sign would be discovered in front of a patient.
            throw new IllegalStateException(
                    "sclinic.signing.provider is '" + properties.provider()
                            + "', which is not a known provider. Supported: "
                            + VnptSmartCaAdapter.PROVIDER_CODE
                            + ". Leave it unset to run without signing.");
        }

        log.warn("No digital signature provider configured. Encounter records can be "
                + "written and kept as drafts, but cannot be signed. Set "
                + "sclinic.signing.provider before going live.");
        return new UnconfiguredSignatureProvider();
    }
}
