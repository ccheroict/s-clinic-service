package com.sclinic.consent;

import lombok.Getter;

/**
 * An action needing consent was attempted without a live one.
 *
 * <p>Requirement 11.5: the refusal has to name the missing consent. A bare "not
 * permitted" leaves the person at the desk with no idea that the fix is a form
 * the patient can sign in a minute, and the predictable result is someone
 * deciding the system is broken and working around it.
 */
@Getter
public class ConsentRequiredException extends RuntimeException {

    private final ConsentTypeCode missing;

    public ConsentRequiredException(ConsentTypeCode missing, String typeName) {
        super("Thiếu sự đồng ý: " + typeName + " (" + missing.name() + "). "
                + "Hãy thu sự đồng ý của bệnh nhân trước khi thực hiện hành động này.");
        this.missing = missing;
    }

    /** Withdrawn consent, distinguished from never having been given. */
    public static ConsentRequiredException withdrawn(ConsentTypeCode missing, String typeName) {
        return new ConsentRequiredException(missing, typeName + " (đã bị rút)");
    }
}
