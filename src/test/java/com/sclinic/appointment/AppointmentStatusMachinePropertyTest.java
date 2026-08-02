package com.sclinic.appointment;

import com.sclinic.appointment.exception.InvalidStatusTransitionException;
import net.jqwik.api.*;

import java.util.*;

/**
 * Property-based tests for AppointmentStatusMachine.
 * Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6
 */
class AppointmentStatusMachinePropertyTest {

    private final AppointmentStatusMachine machine = new AppointmentStatusMachine();

    // Define the expected state transition map (source of truth for test generation)
    private static final Map<AppointmentStatus, Set<AppointmentStatus>> VALID_TRANSITIONS;

    static {
        Map<AppointmentStatus, Set<AppointmentStatus>> map = new EnumMap<>(AppointmentStatus.class);
        map.put(AppointmentStatus.BOOKED, EnumSet.of(
                AppointmentStatus.CONFIRMED,
                AppointmentStatus.CANCELLED,
                AppointmentStatus.NO_SHOW));
        map.put(AppointmentStatus.CONFIRMED, EnumSet.of(
                AppointmentStatus.ARRIVED,
                AppointmentStatus.CANCELLED,
                AppointmentStatus.NO_SHOW));
        map.put(AppointmentStatus.ARRIVED, EnumSet.of(
                AppointmentStatus.IN_PROGRESS,
                AppointmentStatus.CANCELLED));
        map.put(AppointmentStatus.IN_PROGRESS, EnumSet.of(
                AppointmentStatus.DONE));
        map.put(AppointmentStatus.DONE, EnumSet.noneOf(AppointmentStatus.class));
        map.put(AppointmentStatus.CANCELLED, EnumSet.noneOf(AppointmentStatus.class));
        map.put(AppointmentStatus.NO_SHOW, EnumSet.noneOf(AppointmentStatus.class));
        VALID_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    // --- Property 9: Valid state transitions accepted ---

    @Property(tries = 100)
    @Tag("Feature: appointment-booking, Property 9: Valid state transitions accepted")
    void validTransitionsShouldNotThrow(@ForAll("validTransitionPairs") Tuple.Tuple2<AppointmentStatus, AppointmentStatus> pair) {
        AppointmentStatus current = pair.get1();
        AppointmentStatus target = pair.get2();

        // Should not throw for any valid (current, target) pair
        machine.validateTransition(current, target);
    }

    @Provide
    Arbitrary<Tuple.Tuple2<AppointmentStatus, AppointmentStatus>> validTransitionPairs() {
        List<Tuple.Tuple2<AppointmentStatus, AppointmentStatus>> validPairs = new ArrayList<>();
        for (Map.Entry<AppointmentStatus, Set<AppointmentStatus>> entry : VALID_TRANSITIONS.entrySet()) {
            AppointmentStatus from = entry.getKey();
            for (AppointmentStatus to : entry.getValue()) {
                validPairs.add(Tuple.of(from, to));
            }
        }
        return Arbitraries.of(validPairs);
    }

    // --- Property 10: Invalid state transitions rejected with allowed alternatives ---

    @Property(tries = 100)
    @Tag("Feature: appointment-booking, Property 10: Invalid state transitions rejected with allowed alternatives")
    void invalidTransitionsShouldThrowWithCorrectDetails(@ForAll("invalidTransitionPairs") Tuple.Tuple2<AppointmentStatus, AppointmentStatus> pair) {
        AppointmentStatus current = pair.get1();
        AppointmentStatus target = pair.get2();

        try {
            machine.validateTransition(current, target);
            // If we reach here, the transition was unexpectedly accepted
            throw new AssertionError(String.format(
                    "Expected InvalidStatusTransitionException for transition %s → %s, but none was thrown",
                    current, target));
        } catch (InvalidStatusTransitionException ex) {
            // Verify the exception contains correct details
            assert ex.getCurrentStatus() == current :
                    String.format("Expected currentStatus=%s but got %s", current, ex.getCurrentStatus());
            assert ex.getTargetStatus() == target :
                    String.format("Expected targetStatus=%s but got %s", target, ex.getTargetStatus());
            assert ex.getAllowedTransitions().equals(VALID_TRANSITIONS.get(current)) :
                    String.format("Expected allowedTransitions=%s but got %s",
                            VALID_TRANSITIONS.get(current), ex.getAllowedTransitions());
        }
    }

    @Provide
    Arbitrary<Tuple.Tuple2<AppointmentStatus, AppointmentStatus>> invalidTransitionPairs() {
        List<Tuple.Tuple2<AppointmentStatus, AppointmentStatus>> invalidPairs = new ArrayList<>();
        for (AppointmentStatus from : AppointmentStatus.values()) {
            Set<AppointmentStatus> allowed = VALID_TRANSITIONS.get(from);
            for (AppointmentStatus to : AppointmentStatus.values()) {
                if (!allowed.contains(to)) {
                    invalidPairs.add(Tuple.of(from, to));
                }
            }
        }
        return Arbitraries.of(invalidPairs);
    }
}
