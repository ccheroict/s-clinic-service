package com.sclinic.appointment.exception;

/**
 * Thrown when an appointment is scheduled outside business hours.
 * Codes: OUTSIDE_HOURS, SUNDAY, PAST_TIME, END_EXCEEDS_CLOSE
 */
public class BusinessHoursException extends RuntimeException {

    private final String code;

    public BusinessHoursException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
