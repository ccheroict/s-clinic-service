package com.sclinic.catalogue;

import java.util.List;

/**
 * What an import did.
 *
 * <p>Reported per outcome rather than as a single count, because "imported 4,000
 * rows" answers the wrong question. The number that matters is how many existing
 * entries were <em>changed</em>: a catalogue update that silently rewrites the
 * strength of a drug already used in prescriptions is the one worth noticing
 * before it happens.
 *
 * @param added    entries that did not exist before
 * @param updated  existing entries whose details changed
 * @param skipped  entries already present and identical
 * @param rejected rows that could not be read, with the reason and line number
 */
public record CatalogueImportReport(
        int added,
        int updated,
        int skipped,
        List<RejectedRow> rejected
) {

    /**
     * @param line   1-based line number in the source file, so the operator can
     *               find it without counting
     * @param reason what was wrong, in terms of the file rather than the schema
     */
    public record RejectedRow(int line, String reason) {
    }

    public int total() {
        return added + updated + skipped + rejected.size();
    }

    public boolean hasRejections() {
        return !rejected.isEmpty();
    }
}
