package com.sclinic.appointment;

import com.sclinic.appointment.exception.BusinessHoursException;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.*;

/**
 * Property-based tests for BusinessHoursValidator.
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.4
 */
@Tag("Feature: appointment-booking, Property 6: Business hours enforcement")
class BusinessHoursValidatorPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    // Fixed clock at Monday 2025-01-06 10:00:00 Asia/Ho_Chi_Minh
    private static final Instant FIXED_NOW = ZonedDateTime.of(
            2025, 1, 6, 10, 0, 0, 0, ZONE
    ).toInstant();

    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZONE);

    private final BusinessHoursValidator validator = new BusinessHoursValidator(FIXED_CLOCK);

    /**
     * Valid times always accepted:
     * Generate random (scheduledAt, durationMin) where day is Mon-Sat,
     * start time in [08:00, 16:59] in Asia/Ho_Chi_Minh,
     * end time <= 17:00, and scheduledAt is in the future relative to the test clock.
     */
    @Property(tries = 200)
    void validTimesAlwaysAccepted(
            @ForAll("validScheduledAt") Instant scheduledAt,
            @ForAll @IntRange(min = 1, max = 60) int durationMin
    ) {
        ZonedDateTime startZoned = scheduledAt.atZone(ZONE);
        LocalTime startTime = startZoned.toLocalTime();
        LocalTime endTime = startTime.plusMinutes(durationMin);

        // Pre-condition: end must not exceed 17:00
        Assume.that(!endTime.isAfter(LocalTime.of(17, 0)));

        assertThatCode(() -> validator.validate(scheduledAt, durationMin))
                .doesNotThrowAnyException();
    }

    /**
     * Sunday always rejected:
     * Generate random scheduledAt on a Sunday (future).
     * Assert throws with code "SUNDAY".
     */
    @Property(tries = 200)
    void sundayAlwaysRejected(
            @ForAll("futureSunday") Instant scheduledAt,
            @ForAll @IntRange(min = 1, max = 60) int durationMin
    ) {
        BusinessHoursException ex = catchThrowableOfType(
                () -> validator.validate(scheduledAt, durationMin),
                BusinessHoursException.class
        );
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("SUNDAY");
    }

    /**
     * Before open rejected:
     * Generate random scheduledAt with start time before 08:00 (future, Mon-Sat).
     * Assert throws with code "OUTSIDE_HOURS".
     */
    @Property(tries = 200)
    void beforeOpenRejected(
            @ForAll("futureBeforeOpen") Instant scheduledAt,
            @ForAll @IntRange(min = 1, max = 60) int durationMin
    ) {
        BusinessHoursException ex = catchThrowableOfType(
                () -> validator.validate(scheduledAt, durationMin),
                BusinessHoursException.class
        );
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("OUTSIDE_HOURS");
    }

    /**
     * End exceeds close rejected:
     * Generate random (scheduledAt, durationMin) where start is valid
     * but start + duration > 17:00 (future, Mon-Sat).
     * Assert throws with code "END_EXCEEDS_CLOSE".
     */
    @Property(tries = 200)
    void endExceedsCloseRejected(
            @ForAll("scheduledAtForExceedingEnd") Instant scheduledAt,
            @ForAll @IntRange(min = 1, max = 540) int extraMinutes
    ) {
        ZonedDateTime startZoned = scheduledAt.atZone(ZONE);
        LocalTime startTime = startZoned.toLocalTime();
        // Calculate minimum duration to exceed 17:00
        int minutesToClose = (int) Duration.between(startTime, LocalTime.of(17, 0)).toMinutes();
        int durationMin = minutesToClose + extraMinutes;

        // Ensure end actually exceeds 17:00
        Assume.that(durationMin > 0 && startTime.plusMinutes(durationMin).isAfter(LocalTime.of(17, 0)));

        BusinessHoursException ex = catchThrowableOfType(
                () -> validator.validate(scheduledAt, durationMin),
                BusinessHoursException.class
        );
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("END_EXCEEDS_CLOSE");
    }

    /**
     * Past time rejected:
     * Generate random scheduledAt in the past.
     * Assert throws with code "PAST_TIME".
     */
    @Property(tries = 200)
    void pastTimeRejected(
            @ForAll("pastTime") Instant scheduledAt,
            @ForAll @IntRange(min = 1, max = 60) int durationMin
    ) {
        BusinessHoursException ex = catchThrowableOfType(
                () -> validator.validate(scheduledAt, durationMin),
                BusinessHoursException.class
        );
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo("PAST_TIME");
    }

    // --- Arbitraries ---

    /**
     * Generates a future Instant that falls Mon-Sat, 08:00-16:59 in Asia/Ho_Chi_Minh.
     */
    @Provide
    Arbitrary<Instant> validScheduledAt() {
        // Generate days offset 1-365 from FIXED_NOW
        return Arbitraries.integers().between(1, 365).flatMap(dayOffset -> {
            // Generate hour 8-16, minute 0-59
            return Arbitraries.integers().between(8, 16).flatMap(hour ->
                    Arbitraries.integers().between(0, 59).map(minute -> {
                        LocalDate baseDate = LocalDate.of(2025, 1, 6).plusDays(dayOffset);
                        ZonedDateTime zdt = ZonedDateTime.of(baseDate, LocalTime.of(hour, minute), ZONE);

                        // Skip Sundays by shifting to Monday
                        if (zdt.getDayOfWeek() == DayOfWeek.SUNDAY) {
                            zdt = zdt.plusDays(1);
                        }

                        return zdt.toInstant();
                    })
            );
        });
    }

    /**
     * Generates a future Instant on a Sunday within business hours.
     */
    @Provide
    Arbitrary<Instant> futureSunday() {
        return Arbitraries.integers().between(1, 52).flatMap(weekOffset -> {
            // Find next Sunday from the base date
            LocalDate nextSunday = LocalDate.of(2025, 1, 6)
                    .plusWeeks(weekOffset)
                    .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            return Arbitraries.integers().between(8, 16).flatMap(hour ->
                    Arbitraries.integers().between(0, 59).map(minute ->
                            ZonedDateTime.of(nextSunday, LocalTime.of(hour, minute), ZONE).toInstant()
                    )
            );
        });
    }

    /**
     * Generates a future Instant Mon-Sat with start time before 08:00.
     */
    @Provide
    Arbitrary<Instant> futureBeforeOpen() {
        return Arbitraries.integers().between(1, 365).flatMap(dayOffset -> {
            // Generate hour 0-7, minute 0-59
            return Arbitraries.integers().between(0, 7).flatMap(hour ->
                    Arbitraries.integers().between(0, 59).map(minute -> {
                        LocalDate baseDate = LocalDate.of(2025, 1, 6).plusDays(dayOffset);
                        ZonedDateTime zdt = ZonedDateTime.of(baseDate, LocalTime.of(hour, minute), ZONE);

                        // Skip Sundays (would throw SUNDAY first, we want OUTSIDE_HOURS)
                        if (zdt.getDayOfWeek() == DayOfWeek.SUNDAY) {
                            zdt = zdt.plusDays(1);
                        }

                        return zdt.toInstant();
                    })
            );
        });
    }

    /**
     * Generates a future Instant Mon-Sat with start within business hours [08:00, 16:59],
     * specifically biased towards later hours where exceeding close is easy.
     */
    @Provide
    Arbitrary<Instant> scheduledAtForExceedingEnd() {
        // Generate starts at 08:00-16:59 (same as validScheduledAt)
        return validScheduledAt();
    }

    /**
     * Generates an Instant in the past relative to FIXED_NOW.
     */
    @Provide
    Arbitrary<Instant> pastTime() {
        return Arbitraries.integers().between(1, 365 * 24 * 60).map(minutesAgo ->
                FIXED_NOW.minus(Duration.ofMinutes(minutesAgo))
        );
    }
}
