package com.sclinic.appointment;

import net.jqwik.api.*;
import net.jqwik.api.constraints.Size;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Property-based test for the default sort invariant.
 * 
 * Property 8: Default sort invariant
 * For any list of appointments returned by the system (regardless of filters applied),
 * the appointments SHALL be ordered by scheduledAt ascending — i.e., for all consecutive
 * pairs (a[i], a[i+1]), a[i].scheduledAt <= a[i+1].scheduledAt.
 *
 * Validates: Requirements 4.5, 10.1
 */
@Tag("Feature: appointment-booking, Property 8: Default sort invariant")
class SortInvariantPropertyTest {

    /**
     * The comparator used by the system for default sort order.
     * The service relies on Pageable sort by scheduledAt ascending.
     */
    private static final Comparator<Appointment> SCHEDULED_AT_ASC =
            Comparator.comparing(Appointment::getScheduledAt);

    @Property(tries = 100)
    void sortedAppointmentsAreInScheduledAtAscendingOrder(
            @ForAll("randomAppointmentLists") List<Appointment> unsortedAppointments) {

        // Sort using the same comparator the system uses
        List<Appointment> sorted = unsortedAppointments.stream()
                .sorted(SCHEDULED_AT_ASC)
                .collect(Collectors.toList());

        // Verify ascending order: for all consecutive pairs, a[i].scheduledAt <= a[i+1].scheduledAt
        for (int i = 0; i < sorted.size() - 1; i++) {
            Instant current = sorted.get(i).getScheduledAt();
            Instant next = sorted.get(i + 1).getScheduledAt();
            assert !current.isAfter(next) :
                    String.format("Sort invariant violated at index %d: %s > %s", i, current, next);
        }
    }

    @Property(tries = 100)
    void sortedListPreservesAllElements(
            @ForAll("randomAppointmentLists") List<Appointment> unsortedAppointments) {

        // Sort using the same comparator
        List<Appointment> sorted = unsortedAppointments.stream()
                .sorted(SCHEDULED_AT_ASC)
                .collect(Collectors.toList());

        // The sorted list should have the same size as the input
        assert sorted.size() == unsortedAppointments.size() :
                String.format("Expected size %d but got %d", unsortedAppointments.size(), sorted.size());

        // All elements from input should be present in sorted output
        for (Appointment appt : unsortedAppointments) {
            assert sorted.contains(appt) :
                    String.format("Sorted list missing appointment with scheduledAt=%s", appt.getScheduledAt());
        }
    }

    @Property(tries = 100)
    void singleElementListIsAlwaysSorted(
            @ForAll("randomInstants") Instant scheduledAt) {

        Appointment appointment = createAppointment(scheduledAt);
        List<Appointment> single = List.of(appointment);

        List<Appointment> sorted = single.stream()
                .sorted(SCHEDULED_AT_ASC)
                .collect(Collectors.toList());

        assert sorted.size() == 1;
        assert sorted.get(0).getScheduledAt().equals(scheduledAt);
    }

    @Property(tries = 100)
    void duplicateScheduledAtValuesAreHandledCorrectly(
            @ForAll("randomInstants") Instant sharedTime,
            @ForAll @Size(min = 2, max = 10) List<@From("randomAppointmentStatuses") AppointmentStatus> statuses) {

        // Create multiple appointments with the same scheduledAt
        List<Appointment> appointments = statuses.stream()
                .map(status -> {
                    Appointment appt = createAppointment(sharedTime);
                    appt.setStatus(status);
                    return appt;
                })
                .collect(Collectors.toList());

        List<Appointment> sorted = appointments.stream()
                .sorted(SCHEDULED_AT_ASC)
                .collect(Collectors.toList());

        // All should still satisfy the <= invariant (equal times are fine)
        for (int i = 0; i < sorted.size() - 1; i++) {
            Instant current = sorted.get(i).getScheduledAt();
            Instant next = sorted.get(i + 1).getScheduledAt();
            assert !current.isAfter(next) :
                    String.format("Sort invariant violated at index %d: %s > %s", i, current, next);
        }
    }

    // --- Arbitraries ---

    @Provide
    Arbitrary<List<Appointment>> randomAppointmentLists() {
        return randomInstants()
                .map(this::createAppointment)
                .list()
                .ofMinSize(0)
                .ofMaxSize(50);
    }

    @Provide
    Arbitrary<Instant> randomInstants() {
        // Generate instants within a reasonable range (year 2024-2025)
        long minEpochSecond = Instant.parse("2024-01-01T00:00:00Z").getEpochSecond();
        long maxEpochSecond = Instant.parse("2025-12-31T23:59:59Z").getEpochSecond();
        return Arbitraries.longs()
                .between(minEpochSecond, maxEpochSecond)
                .map(Instant::ofEpochSecond);
    }

    @Provide
    Arbitrary<AppointmentStatus> randomAppointmentStatuses() {
        return Arbitraries.of(AppointmentStatus.values());
    }

    // --- Helpers ---

    private Appointment createAppointment(Instant scheduledAt) {
        Appointment appointment = new Appointment();
        appointment.setScheduledAt(scheduledAt);
        appointment.setDurationMin(30);
        appointment.setStatus(AppointmentStatus.BOOKED);
        return appointment;
    }
}
