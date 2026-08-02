package com.sclinic.appointment;

import com.sclinic.appointment.exception.InvalidStatusTransitionException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * State machine for appointment status transitions.
 * Defines allowed transitions and validates state changes.
 */
@Component
public class AppointmentStatusMachine {

    private static final Map<AppointmentStatus, Set<AppointmentStatus>> TRANSITIONS;

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
        // Terminal states - no transitions allowed
        map.put(AppointmentStatus.DONE, EnumSet.noneOf(AppointmentStatus.class));
        map.put(AppointmentStatus.CANCELLED, EnumSet.noneOf(AppointmentStatus.class));
        map.put(AppointmentStatus.NO_SHOW, EnumSet.noneOf(AppointmentStatus.class));
        TRANSITIONS = Collections.unmodifiableMap(map);
    }

    /**
     * Validates that a transition from current to target status is allowed.
     *
     * @param current the current appointment status
     * @param target  the desired target status
     * @throws InvalidStatusTransitionException if the transition is not allowed
     */
    public void validateTransition(AppointmentStatus current, AppointmentStatus target) {
        Set<AppointmentStatus> allowed = allowedTransitions(current);
        if (!allowed.contains(target)) {
            throw new InvalidStatusTransitionException(current, target, allowed);
        }
    }

    /**
     * Returns the set of statuses that can be transitioned to from the given current status.
     *
     * @param current the current appointment status
     * @return unmodifiable set of allowed target statuses (empty for terminal states)
     */
    public Set<AppointmentStatus> allowedTransitions(AppointmentStatus current) {
        return TRANSITIONS.getOrDefault(current, EnumSet.noneOf(AppointmentStatus.class));
    }
}
