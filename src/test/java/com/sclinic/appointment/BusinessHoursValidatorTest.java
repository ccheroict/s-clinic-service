package com.sclinic.appointment;

import com.sclinic.appointment.exception.BusinessHoursException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.assertj.core.api.Assertions.*;

class BusinessHoursValidatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private BusinessHoursValidator validator;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        // Fixed clock: Monday 2024-01-15 at 07:00 Asia/Ho_Chi_Minh
        Instant fixedInstant = LocalDateTime.of(2024, 1, 15, 7, 0)
                .atZone(ZONE)
                .toInstant();
        fixedClock = Clock.fixed(fixedInstant, ZONE);
        validator = new BusinessHoursValidator(fixedClock);
    }

    @Test
    @DisplayName("Valid: Monday 09:00, 30 min → no exception")
    void validMondayMorning() {
        Instant scheduledAt = LocalDateTime.of(2024, 1, 15, 9, 0)
                .atZone(ZONE).toInstant();
        assertThatCode(() -> validator.validate(scheduledAt, 30))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Valid: Saturday 08:00, 60 min → no exception")
    void validSaturdayOpen() {
        Instant scheduledAt = LocalDateTime.of(2024, 1, 20, 8, 0)
                .atZone(ZONE).toInstant();
        assertThatCode(() -> validator.validate(scheduledAt, 60))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Valid: boundary - start at 08:00, end at 17:00 (540 min)")
    void validFullDayBoundary() {
        Instant scheduledAt = LocalDateTime.of(2024, 1, 15, 8, 0)
                .atZone(ZONE).toInstant();
        assertThatCode(() -> validator.validate(scheduledAt, 540))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Valid: end exactly at 17:00")
    void validEndExactlyAtClose() {
        Instant scheduledAt = LocalDateTime.of(2024, 1, 15, 16, 30)
                .atZone(ZONE).toInstant();
        assertThatCode(() -> validator.validate(scheduledAt, 30))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PAST_TIME: scheduledAt is before now")
    void rejectPastTime() {
        Instant past = LocalDateTime.of(2024, 1, 15, 6, 0)
                .atZone(ZONE).toInstant();
        assertThatThrownBy(() -> validator.validate(past, 30))
                .isInstanceOf(BusinessHoursException.class)
                .satisfies(ex -> assertThat(((BusinessHoursException) ex).getCode())
                        .isEqualTo("PAST_TIME"));
    }

    @Test
    @DisplayName("SUNDAY: appointment on Sunday")
    void rejectSunday() {
        Instant sunday = LocalDateTime.of(2024, 1, 21, 10, 0)
                .atZone(ZONE).toInstant();
        assertThatThrownBy(() -> validator.validate(sunday, 30))
                .isInstanceOf(BusinessHoursException.class)
                .satisfies(ex -> assertThat(((BusinessHoursException) ex).getCode())
                        .isEqualTo("SUNDAY"));
    }

    @Test
    @DisplayName("OUTSIDE_HOURS: start before 08:00")
    void rejectBeforeOpen() {
        Instant early = LocalDateTime.of(2024, 1, 15, 7, 30)
                .atZone(ZONE).toInstant();
        assertThatThrownBy(() -> validator.validate(early, 30))
                .isInstanceOf(BusinessHoursException.class)
                .satisfies(ex -> assertThat(((BusinessHoursException) ex).getCode())
                        .isEqualTo("OUTSIDE_HOURS"));
    }

    @Test
    @DisplayName("END_EXCEEDS_CLOSE: end time after 17:00")
    void rejectEndAfterClose() {
        Instant lateStart = LocalDateTime.of(2024, 1, 15, 16, 31)
                .atZone(ZONE).toInstant();
        assertThatThrownBy(() -> validator.validate(lateStart, 30))
                .isInstanceOf(BusinessHoursException.class)
                .satisfies(ex -> assertThat(((BusinessHoursException) ex).getCode())
                        .isEqualTo("END_EXCEEDS_CLOSE"));
    }

    @Test
    @DisplayName("Priority: PAST_TIME checked before SUNDAY")
    void pastTimePriorityOverSunday() {
        // A past Sunday should throw PAST_TIME (checked first)
        Instant pastSunday = LocalDateTime.of(2024, 1, 14, 10, 0)
                .atZone(ZONE).toInstant();
        assertThatThrownBy(() -> validator.validate(pastSunday, 30))
                .isInstanceOf(BusinessHoursException.class)
                .satisfies(ex -> assertThat(((BusinessHoursException) ex).getCode())
                        .isEqualTo("PAST_TIME"));
    }

    @Test
    @DisplayName("OUTSIDE_HOURS: start at or after 17:00")
    void rejectStartAtOrAfterClose() {
        Instant atClose = LocalDateTime.of(2024, 1, 15, 17, 0)
                .atZone(ZONE).toInstant();
        assertThatThrownBy(() -> validator.validate(atClose, 30))
                .isInstanceOf(BusinessHoursException.class)
                .satisfies(ex -> assertThat(((BusinessHoursException) ex).getCode())
                        .isEqualTo("OUTSIDE_HOURS"));
    }

    @Test
    @DisplayName("Priority: SUNDAY checked before OUTSIDE_HOURS")
    void sundayPriorityOverOutsideHours() {
        // Future Sunday at 06:00 should throw SUNDAY (checked before OUTSIDE_HOURS)
        Instant futureSundayEarly = LocalDateTime.of(2024, 1, 28, 6, 0)
                .atZone(ZONE).toInstant();
        assertThatThrownBy(() -> validator.validate(futureSundayEarly, 30))
                .isInstanceOf(BusinessHoursException.class)
                .satisfies(ex -> assertThat(((BusinessHoursException) ex).getCode())
                        .isEqualTo("SUNDAY"));
    }
}
