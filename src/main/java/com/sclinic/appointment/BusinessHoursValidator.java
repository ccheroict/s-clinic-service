package com.sclinic.appointment;

import com.sclinic.appointment.exception.BusinessHoursException;
import org.springframework.stereotype.Component;

import java.time.*;

/**
 * Validates that appointment times fall within business hours.
 * Business hours: Monday-Saturday, 08:00-17:00 (Asia/Ho_Chi_Minh).
 */
@Component
public class BusinessHoursValidator {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalTime OPEN = LocalTime.of(8, 0);
    private static final LocalTime CLOSE = LocalTime.of(17, 0);

    private final Clock clock;

    public BusinessHoursValidator(Clock clock) {
        this.clock = clock;
    }

    /**
     * Validate that both start time and end time fall within business hours.
     * <ul>
     *   <li>Monday - Saturday only (no Sunday)</li>
     *   <li>08:00 - 17:00 (Asia/Ho_Chi_Minh)</li>
     *   <li>Not in the past</li>
     *   <li>End time (scheduledAt + durationMin) must not exceed 17:00</li>
     * </ul>
     *
     * @param scheduledAt the appointment start time
     * @param durationMin the appointment duration in minutes
     * @throws BusinessHoursException with specific error codes
     */
    public void validate(Instant scheduledAt, int durationMin) {
        Instant now = clock.instant();
        if (scheduledAt.isBefore(now)) {
            throw new BusinessHoursException(
                    "Thời gian hẹn phải ở trong tương lai", "PAST_TIME");
        }

        ZonedDateTime startZoned = scheduledAt.atZone(ZONE);

        if (startZoned.getDayOfWeek() == DayOfWeek.SUNDAY) {
            throw new BusinessHoursException(
                    "Phòng khám không làm việc vào Chủ nhật", "SUNDAY");
        }

        LocalTime startTime = startZoned.toLocalTime();
        if (startTime.isBefore(OPEN) || !startTime.isBefore(CLOSE)) {
            throw new BusinessHoursException(
                    "Thời gian hẹn nằm ngoài giờ làm việc (08:00 - 17:00)", "OUTSIDE_HOURS");
        }

        LocalTime endTime = startTime.plusMinutes(durationMin);
        if (endTime.isAfter(CLOSE)) {
            throw new BusinessHoursException(
                    "Thời gian kết thúc vượt quá giờ đóng cửa (17:00)", "END_EXCEEDS_CLOSE");
        }
    }
}
