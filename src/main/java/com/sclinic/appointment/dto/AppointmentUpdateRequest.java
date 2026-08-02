package com.sclinic.appointment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;
import java.util.UUID;

/**
 * Request payload for updating an existing appointment.
 */
public record AppointmentUpdateRequest(
        Instant scheduledAt,
        @Min(15) @Max(120) Integer durationMin,
        UUID doctorId,
        String reason,
        String note
) {}
