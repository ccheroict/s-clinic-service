package com.sclinic.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How long a record must be kept.
 *
 * <p>The duration is configuration, so these tests supply it. What they pin down
 * is the part that is code: which date starts the clock, and what happens when no
 * policy has been configured at all.
 */
class RetentionCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");
    private static final LocalDate CREATED = LocalDate.of(2020, 3, 15);
    private static final LocalDate WITHDRAWN = LocalDate.of(2024, 7, 1);
    private static final LocalDate DIED = LocalDate.of(2023, 11, 20);

    private RetentionPolicyRepository policyRepository;
    private RetentionCalculator calculator;

    @BeforeEach
    void setUp() {
        policyRepository = mock(RetentionPolicyRepository.class);
        calculator = new RetentionCalculator(policyRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void policy(int years, Integer yearsAfterDeath) {
        RetentionPolicy policy = new RetentionPolicy();
        policy.setRecordType("patient");
        policy.setRetentionYears(years);
        policy.setRetentionYearsAfterDeath(yearsAfterDeath);
        policy.setLegalBasis("Test fixture");
        when(policyRepository.findByRecordType("patient")).thenReturn(Optional.of(policy));
    }

    private Optional<LocalDate> compute(LocalDate created, LocalDate withdrawn, LocalDate deceased) {
        return calculator.retentionUntil("patient", created, withdrawn, deceased);
    }

    @Nested
    class WhichDateStartsTheClock {

        @Test
        void anActiveRecordCountsFromItsCreation() {
            policy(10, null);

            assertThat(compute(CREATED, null, null))
                    .contains(LocalDate.of(2030, 3, 15));
        }

        /**
         * A retention period is normally counted from when a record stops being
         * used, not from when it was opened. Counting from creation after
         * withdrawal would make a long-running record destructible the moment it
         * was withdrawn.
         */
        @Test
        void aWithdrawnRecordCountsFromItsWithdrawal() {
            policy(10, null);

            assertThat(compute(CREATED, WITHDRAWN, null))
                    .contains(LocalDate.of(2034, 7, 1));
        }

        @Test
        void aDeceasedPatientCountsFromTheDateOfDeath() {
            policy(10, 20);

            assertThat(compute(CREATED, WITHDRAWN, DIED))
                    .contains(LocalDate.of(2043, 11, 20));
        }

        /**
         * A rule written for the deceased case exists to override the ordinary
         * one, so it wins even when the record was also withdrawn later.
         */
        @Test
        void theDeathRuleOutranksTheWithdrawalRule() {
            policy(10, 20);

            Optional<LocalDate> fromDeath = compute(CREATED, WITHDRAWN, DIED);
            Optional<LocalDate> fromWithdrawal = compute(CREATED, WITHDRAWN, null);

            assertThat(fromDeath).isNotEqualTo(fromWithdrawal);
            assertThat(fromDeath).contains(DIED.plusYears(20));
        }

        /**
         * With no separate rule for the deceased case, a date of death changes
         * nothing: the ordinary period still applies.
         */
        @Test
        void aDateOfDeathIsIgnoredWhenThePolicyHasNoRuleForIt() {
            policy(10, null);

            assertThat(compute(CREATED, WITHDRAWN, DIED))
                    .isEqualTo(compute(CREATED, WITHDRAWN, null));
        }

        @Test
        void fallsBackToTodayForARecordWithNoCreationDateYet() {
            policy(10, null);

            assertThat(compute(null, null, null))
                    .contains(LocalDate.of(2036, 8, 29));
        }
    }

    @Nested
    class WithoutAConfiguredPolicy {

        /**
         * The central safety property. A retention period is a legal question, so
         * the system holds no default: with nothing configured it returns no date,
         * every record's retention date stays null, and nothing can be judged past
         * its period. An unconfigured system keeps everything.
         */
        @Test
        void producesNoRetentionDateAtAll() {
            when(policyRepository.findByRecordType("patient")).thenReturn(Optional.empty());

            assertThat(compute(CREATED, WITHDRAWN, DIED)).isEmpty();
        }

        @Test
        void reportsThatNoPolicyExists() {
            when(policyRepository.findByRecordType("patient")).thenReturn(Optional.empty());

            assertThat(calculator.hasPolicyFor("patient")).isFalse();
        }

        @Test
        void doesNotApplyOneRecordTypesPolicyToAnother() {
            policy(10, null);
            when(policyRepository.findByRecordType("encounter")).thenReturn(Optional.empty());

            assertThat(calculator.retentionUntil("encounter", CREATED, null, null)).isEmpty();
        }
    }

    @Nested
    class LeapDays {

        /**
         * 29 February plus a whole number of years lands on 28 February in a
         * non-leap year. Worth pinning down because the alternative, throwing or
         * rolling into March, would be a silent off-by-one in a date that governs
         * when a medical record may be destroyed.
         */
        @Test
        void aLeapDayAnchorMovesBackNotForward() {
            policy(3, null);

            assertThat(compute(LocalDate.of(2024, 2, 29), null, null))
                    .contains(LocalDate.of(2027, 2, 28));
        }
    }
}
