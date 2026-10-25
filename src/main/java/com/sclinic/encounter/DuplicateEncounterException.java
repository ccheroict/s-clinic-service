package com.sclinic.encounter;

import java.util.UUID;

/**
 * An encounter already exists for this appointment.
 *
 * <p>One visit, one record. Two would make "what happened at this appointment" a
 * question with two answers, and whichever a reader found first would become the
 * history.
 *
 * <p>The unique index catches this too, but on its own it produces a constraint
 * violation and a 500 — a server fault, which this is not. The caller is usually
 * a second browser tab or a double-clicked button, and what they need is the id
 * of the record that already exists so they can continue writing it.
 */
public class DuplicateEncounterException extends RuntimeException {

    private final UUID existingEncounterId;

    public DuplicateEncounterException(UUID appointmentId, UUID existingEncounterId) {
        super("Lịch hẹn " + appointmentId + " đã có bản ghi khám (" + existingEncounterId
                + "). Mỗi lịch hẹn chỉ có một bản ghi khám; hãy mở bản ghi đó để tiếp tục.");
        this.existingEncounterId = existingEncounterId;
    }

    public UUID getExistingEncounterId() {
        return existingEncounterId;
    }
}
