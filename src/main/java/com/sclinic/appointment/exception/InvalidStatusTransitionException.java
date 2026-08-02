package com.sclinic.appointment.exception;

import com.sclinic.appointment.AppointmentStatus;

import java.util.Set;

/**
 * Thrown when an invalid appointment status transition is attempted.
 */
public class InvalidStatusTransitionException extends RuntimeException {

    private final AppointmentStatus currentStatus;
    private final AppointmentStatus targetStatus;
    private final Set<AppointmentStatus> allowedTransitions;

    public InvalidStatusTransitionException(AppointmentStatus currentStatus,
                                            AppointmentStatus targetStatus,
                                            Set<AppointmentStatus> allowedTransitions) {
        super(String.format("Cannot transition from %s to %s. Allowed transitions: %s",
                currentStatus, targetStatus, allowedTransitions));
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
        this.allowedTransitions = allowedTransitions;
    }

    public AppointmentStatus getCurrentStatus() {
        return currentStatus;
    }

    public AppointmentStatus getTargetStatus() {
        return targetStatus;
    }

    public Set<AppointmentStatus> getAllowedTransitions() {
        return allowedTransitions;
    }
}
