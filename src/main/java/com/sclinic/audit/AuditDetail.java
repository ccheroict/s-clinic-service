package com.sclinic.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@code detail} map of an audit entry.
 *
 * <p>This type exists to make one rule hard to break by accident: <b>the audit
 * trail records that a field changed, not what it changed to.</b> An audit table
 * full of old and new values becomes a second copy of the patient record, with
 * none of the protections the real one gets — no encryption at rest, no
 * retention rule, and readable by anyone who can read the log. It would also
 * survive the deletion of the record it describes, which defeats the point of
 * honouring a deletion request at all.
 *
 * <p>The one exception is {@link #transition}, for fields whose values are a
 * fixed set of codes (an appointment status, for instance). Those carry no
 * personal information and knowing the direction of the change is most of the
 * forensic value, so recording it costs nothing and loses nothing.
 */
public final class AuditDetail {

    private static final String CHANGED = "changed";

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private AuditDetail() {
    }

    public static AuditDetail builder() {
        return new AuditDetail();
    }

    /** Records that a field changed, without saying to what. */
    public AuditDetail changed(String field) {
        fields.put(field, CHANGED);
        return this;
    }

    /**
     * Records a move between two values of a closed set of codes.
     *
     * <p>Only for enum-like fields. Never use this for free text or anything
     * derived from patient data.
     */
    public AuditDetail transition(String field, Enum<?> from, Enum<?> to) {
        fields.put(field, Map.of(
                "from", from == null ? "" : from.name(),
                "to", to == null ? "" : to.name()));
        return this;
    }

    /** Records a plain code, such as the reason an admin overrode a rule. */
    public AuditDetail code(String field, String code) {
        fields.put(field, code == null ? "" : code);
        return this;
    }

    /**
     * @return the detail map, or null when nothing was recorded, since a null
     *         column reads better than an empty object
     */
    public Map<String, Object> build() {
        return fields.isEmpty() ? null : Map.copyOf(fields);
    }
}
