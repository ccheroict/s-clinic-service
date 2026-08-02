package com.sclinic.appointment;

import com.sclinic.appointment.exception.InvalidStatusTransitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentStatusMachineTest {

    private AppointmentStatusMachine machine;

    @BeforeEach
    void setUp() {
        machine = new AppointmentStatusMachine();
    }

    // --- allowedTransitions tests ---

    @Test
    void allowedTransitions_fromBooked_returnsConfirmedCancelledNoShow() {
        Set<AppointmentStatus> allowed = machine.allowedTransitions(AppointmentStatus.BOOKED);
        assertThat(allowed).containsExactlyInAnyOrder(
                AppointmentStatus.CONFIRMED,
                AppointmentStatus.CANCELLED,
                AppointmentStatus.NO_SHOW);
    }

    @Test
    void allowedTransitions_fromConfirmed_returnsArrivedCancelledNoShow() {
        Set<AppointmentStatus> allowed = machine.allowedTransitions(AppointmentStatus.CONFIRMED);
        assertThat(allowed).containsExactlyInAnyOrder(
                AppointmentStatus.ARRIVED,
                AppointmentStatus.CANCELLED,
                AppointmentStatus.NO_SHOW);
    }

    @Test
    void allowedTransitions_fromArrived_returnsInProgressCancelled() {
        Set<AppointmentStatus> allowed = machine.allowedTransitions(AppointmentStatus.ARRIVED);
        assertThat(allowed).containsExactlyInAnyOrder(
                AppointmentStatus.IN_PROGRESS,
                AppointmentStatus.CANCELLED);
    }

    @Test
    void allowedTransitions_fromInProgress_returnsDone() {
        Set<AppointmentStatus> allowed = machine.allowedTransitions(AppointmentStatus.IN_PROGRESS);
        assertThat(allowed).containsExactlyInAnyOrder(AppointmentStatus.DONE);
    }

    @Test
    void allowedTransitions_fromDone_returnsEmpty() {
        Set<AppointmentStatus> allowed = machine.allowedTransitions(AppointmentStatus.DONE);
        assertThat(allowed).isEmpty();
    }

    @Test
    void allowedTransitions_fromCancelled_returnsEmpty() {
        Set<AppointmentStatus> allowed = machine.allowedTransitions(AppointmentStatus.CANCELLED);
        assertThat(allowed).isEmpty();
    }

    @Test
    void allowedTransitions_fromNoShow_returnsEmpty() {
        Set<AppointmentStatus> allowed = machine.allowedTransitions(AppointmentStatus.NO_SHOW);
        assertThat(allowed).isEmpty();
    }

    // --- validateTransition valid cases ---

    @ParameterizedTest
    @CsvSource({
            "BOOKED, CONFIRMED",
            "BOOKED, CANCELLED",
            "BOOKED, NO_SHOW",
            "CONFIRMED, ARRIVED",
            "CONFIRMED, CANCELLED",
            "CONFIRMED, NO_SHOW",
            "ARRIVED, IN_PROGRESS",
            "ARRIVED, CANCELLED",
            "IN_PROGRESS, DONE"
    })
    void validateTransition_validTransitions_doesNotThrow(AppointmentStatus current, AppointmentStatus target) {
        machine.validateTransition(current, target);
        // No exception means success
    }

    // --- validateTransition invalid cases ---

    @ParameterizedTest
    @CsvSource({
            "BOOKED, ARRIVED",
            "BOOKED, IN_PROGRESS",
            "BOOKED, DONE",
            "CONFIRMED, BOOKED",
            "CONFIRMED, IN_PROGRESS",
            "CONFIRMED, DONE",
            "ARRIVED, BOOKED",
            "ARRIVED, CONFIRMED",
            "ARRIVED, DONE",
            "ARRIVED, NO_SHOW",
            "IN_PROGRESS, BOOKED",
            "IN_PROGRESS, CONFIRMED",
            "IN_PROGRESS, ARRIVED",
            "IN_PROGRESS, CANCELLED",
            "IN_PROGRESS, NO_SHOW",
            "DONE, BOOKED",
            "DONE, CONFIRMED",
            "DONE, CANCELLED",
            "CANCELLED, BOOKED",
            "CANCELLED, CONFIRMED",
            "NO_SHOW, BOOKED",
            "NO_SHOW, CONFIRMED"
    })
    void validateTransition_invalidTransitions_throwsException(AppointmentStatus current, AppointmentStatus target) {
        assertThatThrownBy(() -> machine.validateTransition(current, target))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .satisfies(ex -> {
                    InvalidStatusTransitionException e = (InvalidStatusTransitionException) ex;
                    assertThat(e.getCurrentStatus()).isEqualTo(current);
                    assertThat(e.getTargetStatus()).isEqualTo(target);
                    assertThat(e.getAllowedTransitions()).isEqualTo(machine.allowedTransitions(current));
                });
    }

    @Test
    void validateTransition_terminalStateDone_throwsWithEmptyAllowed() {
        assertThatThrownBy(() -> machine.validateTransition(AppointmentStatus.DONE, AppointmentStatus.BOOKED))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .satisfies(ex -> {
                    InvalidStatusTransitionException e = (InvalidStatusTransitionException) ex;
                    assertThat(e.getAllowedTransitions()).isEmpty();
                });
    }
}
