package com.sclinic.security.mfa;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which time step a code belonged to.
 *
 * <p>{@code verify} answering yes or no is not enough to stop a replay: inside a
 * one-step drift window a code is valid for about ninety seconds, and "is this
 * code valid" keeps answering yes however many times it is asked. Knowing the
 * step is what lets a caller keep a high-water mark and refuse anything that is
 * not newer.
 */
class TotpReplayTest {

    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final Instant AT = Instant.parse("2026-10-19T03:00:00Z");

    @Test
    void aValidCodeReportsTheStepItCameFrom() {
        long step = TotpGenerator.timeStep(AT);
        String code = TotpGenerator.codeAt(SECRET, AT);

        assertThat(TotpGenerator.matchingStep(SECRET, code, AT, 1)).isEqualTo(step);
    }

    @Test
    void aCodeFromThePreviousStepReportsThatStepAndNotTheCurrentOne() {
        Instant earlier = AT.minusSeconds(TotpGenerator.TIME_STEP_SECONDS);
        String code = TotpGenerator.codeAt(SECRET, earlier);

        // The distinction the replay check depends on. Reporting the current step
        // for a code from the previous one would let that code be used again in
        // the next window, and again in the one after.
        assertThat(TotpGenerator.matchingStep(SECRET, code, AT, 1))
                .isEqualTo(TotpGenerator.timeStep(earlier));
    }

    @Test
    void aCodeFromTheNextStepIsAcceptedAndReportsThatStep() {
        Instant later = AT.plusSeconds(TotpGenerator.TIME_STEP_SECONDS);
        String code = TotpGenerator.codeAt(SECRET, later);

        assertThat(TotpGenerator.matchingStep(SECRET, code, AT, 1))
                .isEqualTo(TotpGenerator.timeStep(later));
    }

    @Test
    void aWrongCodeReportsNoMatch() {
        assertThat(TotpGenerator.matchingStep(SECRET, "000000", AT, 1))
                .isEqualTo(TotpGenerator.NO_MATCH);
    }

    @Test
    void noMatchIsDistinguishableFromStepZero() {
        // Step 0 is 1 January 1970, which no clinic will see, but -1 or 0 as a
        // sentinel would be a real step and the comparison "step greater than
        // last" would quietly accept it.
        assertThat(TotpGenerator.NO_MATCH).isLessThan(0L);
        assertThat(TotpGenerator.NO_MATCH).isNotEqualTo(-1L);
    }

    @Test
    void outsideTheWindowThereIsNoMatch() {
        Instant farOff = AT.minusSeconds(TotpGenerator.TIME_STEP_SECONDS * 5L);
        String code = TotpGenerator.codeAt(SECRET, farOff);

        assertThat(TotpGenerator.matchingStep(SECRET, code, AT, 1))
                .isEqualTo(TotpGenerator.NO_MATCH);
    }

    @Test
    void aZeroWidthWindowAcceptsOnlyTheCurrentStep() {
        String current = TotpGenerator.codeAt(SECRET, AT);
        String previous = TotpGenerator.codeAt(
                SECRET, AT.minusSeconds(TotpGenerator.TIME_STEP_SECONDS));

        assertThat(TotpGenerator.matchingStep(SECRET, current, AT, 0))
                .isEqualTo(TotpGenerator.timeStep(AT));
        assertThat(TotpGenerator.matchingStep(SECRET, previous, AT, 0))
                .isEqualTo(TotpGenerator.NO_MATCH);
    }

    @Test
    void nullsAndMalformedInputReportNoMatchRatherThanThrowing() {
        assertThat(TotpGenerator.matchingStep(null, "123456", AT, 1))
                .isEqualTo(TotpGenerator.NO_MATCH);
        assertThat(TotpGenerator.matchingStep(SECRET, null, AT, 1))
                .isEqualTo(TotpGenerator.NO_MATCH);
        assertThat(TotpGenerator.matchingStep(SECRET, "12345", AT, 1))
                .isEqualTo(TotpGenerator.NO_MATCH);
        assertThat(TotpGenerator.matchingStep(SECRET, "1234567", AT, 1))
                .isEqualTo(TotpGenerator.NO_MATCH);
    }

    @Test
    void verifyStillAgreesWithMatchingStep() {
        String code = TotpGenerator.codeAt(SECRET, AT);

        // verify is now a wrapper. Keeping it is worthwhile — the RFC 6238 test
        // vectors read better against a boolean — but it must not drift.
        assertThat(TotpGenerator.verify(SECRET, code, AT, 1)).isTrue();
        assertThat(TotpGenerator.verify(SECRET, "000000", AT, 1)).isFalse();
    }
}
