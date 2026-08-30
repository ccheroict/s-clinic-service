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
    VERIFY_CHAIN
}
