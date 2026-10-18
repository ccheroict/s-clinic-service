package com.sclinic.audit;

/**
 * Auditable action performed on a record.
 */
public enum AuditAction {
    VIEW,
    CREATE,
    UPDATE,
    DELETE,

    /**
     * An administrator checked the integrity of the trail. Recorded so that the
     * checks themselves leave a history, and so a run of the check can be tied
     * to the head hash it reported.
     */
    VERIFY_CHAIN,

    /**
     * A clinician opened a record they had no treating relationship with.
     *
     * <p>Its own action rather than a severity flag on the entry: the action is
     * part of what the audit hash covers, so it cannot be quietly downgraded to
     * something unremarkable later. A severity column added outside the hash
     * could be.
     */
    BREAK_GLASS,

    /** An administrator ended someone's break-glass access early. */
    BREAK_GLASS_REVOKED,

    /**
     * A reference catalogue was loaded from file. Recorded because a catalogue
     * update changes what can be prescribed and how it is coded, and the counts
     * are what tell a later review whether a given import changed existing
     * entries or only added new ones.
     */
    IMPORT
}
