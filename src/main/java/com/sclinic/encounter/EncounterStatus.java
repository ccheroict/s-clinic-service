package com.sclinic.encounter;

/**
 * Where an encounter record stands in its life.
 *
 * <p>Only {@link #DRAFT} is editable. The other two exist because a medical
 * record, once signed, is evidence: Luật KCB 15/2023 lets an electronic record
 * replace the paper one, and what makes that acceptable is that the signed
 * version cannot quietly become a different version.
 */
public enum EncounterStatus {

    /** Being written. The only state in which the content can change. */
    DRAFT,

    /**
     * Signed, and locked at both the application and the database level.
     *
     * <p>Two levels because one is not enough: a service check protects callers
     * that go through the service, and a medical record has to survive the ones
     * that do not — a migration, a fix-up script, a second application written
     * against the same database in five years.
     */
    SIGNED,

    /**
     * Signed, and corrected by a later signed amendment.
     *
     * <p>The row itself is still exactly what was signed. This status says only
     * that a reader should follow the chain forward, which is why correcting a
     * record does not mean editing it.
     */
    AMENDED
}
