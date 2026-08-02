package com.sclinic.appointment.exception;

import com.sclinic.appointment.AppointmentStatus;

/**
 * Thrown when an appointment cannot be edited due to its current status.
 */
public class NotEditableException extends RuntimeException {

    private final AppointmentStatus currentStatus;

    public NotEditableException(AppointmentStatus currentStatus) {
        super(String.format("Appointment with status %s cannot be edited", currentStatus));
        this.currentStatus = currentStatus;
    }

    public AppointmentStatus getCurrentStatus() {
        return currentStatus;
    }
}
