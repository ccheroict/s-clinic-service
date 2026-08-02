package com.sclinic.appointment.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Request payload for creating a new appointment.
 */
public record AppointmentCreateRequest(
        @NotNull UUID patientId,
        UUID doctorId,
        @NotNull @Future Instant scheduledAt,
        @Min(5) @Max(240) Integer durationMin,
        String reason,
        String note
) {}
