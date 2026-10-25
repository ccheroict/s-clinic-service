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
    IMPORT,

    /**
     * Housekeeping removed rows that had outlived their purpose.
     *
     * <p>Distinct from {@link #DELETE}, which is somebody deciding to stop using
     * one record. This is a scheduled job trimming a table, and the two must not
     * read alike: a review looking for who discarded a patient's record should
     * not have to sift nightly maintenance out of the results.
     *
     * <p>Audited at all because a purge that leaves no trace cannot be told apart
     * from tampering, and these purges remove rows that security reviews read.
     */
    PURGE
}
