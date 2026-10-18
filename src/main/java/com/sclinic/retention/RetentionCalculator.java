package com.sclinic.retention;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Works out the earliest date a record may be considered for destruction.
 */
@Component
@RequiredArgsConstructor
public class RetentionCalculator {

    private final RetentionPolicyRepository policyRepository;
    private final Clock clock;

    /**
     * @param recordType  which policy applies, e.g. {@code "patient"}
     * @param createdOn   when the record was created
     * @param withdrawnOn when the record was withdrawn from use, or null if still active
     * @param deceasedOn  the patient's date of death, or null
     * @return the retention date, or empty when no policy is configured
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> retentionUntil(String recordType, LocalDate createdOn,
                                              LocalDate withdrawnOn, LocalDate deceasedOn) {
        return policyRepository.findByRecordType(recordType)
                .map(policy -> apply(policy, createdOn, withdrawnOn, deceasedOn));
    }

    /**
     * Picks the clock to count from, then adds the configured number of years.
     *
     * <p>Which date starts the clock is a design decision and lives here; how many
     * years it runs for is a legal one and lives in the policy row. The order
     * below is deliberate:
     *
     * <ol>
     *   <li>Death, when the policy defines a separate period for it. A rule written
     *       for the deceased case exists precisely to override the ordinary one.</li>
     *   <li>Withdrawal from use. Retention periods are normally counted from when a
     *       record stops being active, not from when it was opened.</li>
     *   <li>Creation, while the record is still in use. This gives an active record
     *       a retention date too, so the field is never empty for want of an event
     *       that has not happened yet.</li>
     * </ol>
     */
    private LocalDate apply(RetentionPolicy policy, LocalDate createdOn,
                            LocalDate withdrawnOn, LocalDate deceasedOn) {
        if (deceasedOn != null && policy.getRetentionYearsAfterDeath() != null) {
            return deceasedOn.plusYears(policy.getRetentionYearsAfterDeath());
        }

        LocalDate anchor = withdrawnOn != null ? withdrawnOn : createdOn;
        if (anchor == null) {
            // A record with no creation date yet (not flushed); count from today
            // rather than returning nothing, so the field is still populated.
            anchor = LocalDate.now(clock);
        }
        return anchor.plusYears(policy.getRetentionYears());
    }

    /** Whether any policy is configured at all, for diagnostics and tests. */
    @Transactional(readOnly = true)
    public boolean hasPolicyFor(String recordType) {
        return policyRepository.findByRecordType(recordType).isPresent();
    }
}
