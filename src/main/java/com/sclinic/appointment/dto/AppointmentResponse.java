package com.sclinic.appointment.dto;

import com.sclinic.appointment.AppointmentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload representing an appointment with resolved patient/doctor names.
 */
public record AppointmentResponse(
        UUID id,
        UUID patientId,
        String patientName,
        String patientPhone,
        UUID doctorId,
        String doctorName,
        Instant scheduledAt,
        int durationMin,
        AppointmentStatus status,
        String reason,
        String note,
        Instant createdAt,
        Instant updatedAt
) {}
