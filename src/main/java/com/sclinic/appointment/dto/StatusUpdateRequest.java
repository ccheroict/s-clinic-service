package com.sclinic.appointment.dto;

import com.sclinic.appointment.AppointmentStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload for updating appointment status.
 */
public record StatusUpdateRequest(
        @NotNull AppointmentStatus status
) {}
