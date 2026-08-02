package com.sclinic.appointment.exception;

import java.time.Instant;
import java.util.UUID;

/**
 * Thrown when a scheduling conflict is detected (overlapping time slots).
 */
public class ConflictException extends RuntimeException {

    private final UUID conflictingAppointmentId;
    private final String conflictingPatientName;
    private final Instant conflictingScheduledAt;
    private final int conflictingDurationMin;

    public ConflictException(String message, UUID conflictingAppointmentId,
                             String conflictingPatientName, Instant conflictingScheduledAt,
                             int conflictingDurationMin) {
        super(message);
        this.conflictingAppointmentId = conflictingAppointmentId;
        this.conflictingPatientName = conflictingPatientName;
        this.conflictingScheduledAt = conflictingScheduledAt;
        this.conflictingDurationMin = conflictingDurationMin;
    }

    public UUID getConflictingAppointmentId() {
        return conflictingAppointmentId;
    }

    public String getConflictingPatientName() {
        return conflictingPatientName;
    }

    public Instant getConflictingScheduledAt() {
        return conflictingScheduledAt;
    }

    public int getConflictingDurationMin() {
        return conflictingDurationMin;
    }
}
