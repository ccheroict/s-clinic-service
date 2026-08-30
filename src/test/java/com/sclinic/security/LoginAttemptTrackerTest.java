package com.sclinic.security;

import com.sclinic.security.authevent.AuthEventLogger;
import com.sclinic.security.authevent.AuthEventType;
import com.sclinic.security.authevent.RequestContext;
import com.sclinic.security.session.SessionTokenService;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lockout counter. Threshold is 3 in these tests.
 */
class LoginAttemptTrackerTest {

    private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");
    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final RequestContext CONTEXT = new RequestContext("10.0.0.5", "JUnit");

    private StaffRepository staffRepository;
    private SessionTokenService sessionTokenService;
    private AuthEventLogger authEventLogger;
    private LoginAttemptTracker tracker;

    private Staff staff;

    @BeforeEach
    void setUp() {
        staffRepository = mock(StaffRepository.class);
        sessionTokenService = mock(SessionTokenService.class);
        authEventLogger = mock(AuthEventLogger.class);

        AuthProperties properties = new AuthProperties(
                Duration.ofHours(8), Duration.ofMinutes(10), 3, LOCKOUT, 5, 12,
                Set.of(), 1, 10, "S-Clinic");

        tracker = new LoginAttemptTracker(staffRepository, sessionTokenService, authEventLogger,
                properties, Clock.fixed(NOW, ZoneOffset.UTC));

        staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUsername("bacsi");
        staff.setRole("DOCTOR");
        staff.setActive(true);
        when(staffRepository.findByIdForUpdate(staff.getId())).thenReturn(Optional.of(staff));
    }

    private boolean register(AuthEventType type) {
        return tracker.registerFailure(staff.getId(), staff.getUsername(), CONTEXT, type, "detail");
    }

    @Test
    void countsAnAttemptWithoutLockingBelowTheThreshold() {
        boolean locked = register(AuthEventType.LOGIN_FAILED);

        assertThat(locked).isFalse();
        assertThat(staff.getFailedAttempts()).isEqualTo(1);
        assertThat(staff.getLockedUntil()).isNull();
        verify(staffRepository).save(staff);
    }

    @Test
    void locksTheAccountOnReachingTheThreshold() {
        staff.setFailedAttempts(2);

        boolean locked = register(AuthEventType.LOGIN_FAILED);

        assertThat(locked).isTrue();
        assertThat(staff.getLockedUntil()).isEqualTo(NOW.plus(LOCKOUT));
        assertThat(staff.getFailedAttempts()).isZero();
    }

    @Test
    void recordsBothTheFailureAndTheLock() {
        staff.setFailedAttempts(2);

        register(AuthEventType.LOGIN_FAILED);

        verify(authEventLogger).failureInCurrentTransaction(eq(AuthEventType.LOGIN_FAILED),
                eq("bacsi"), eq(staff.getId()), any(), any());
        verify(authEventLogger).failureInCurrentTransaction(eq(AuthEventType.ACCOUNT_LOCKED),
                eq("bacsi"), eq(staff.getId()), any(), any());
    }

    @Test
    void writesTheEventInItsOwnTransactionRatherThanANestedOne() {
        register(AuthEventType.LOGIN_FAILED);

        // A nested transaction would hold a third database connection for every
        // failed login; this one already commits independently of the request.
        verify(authEventLogger, never()).failure(any(), any(), any(), any(), any());
    }

    @Test
    void dropsLiveSessionsWhenLocking() {
        staff.setFailedAttempts(2);

        register(AuthEventType.LOGIN_FAILED);

        verify(sessionTokenService).revokeAllForStaff(staff.getId());
    }

    @Test
    void leavesSessionsAloneWhileStillBelowTheThreshold() {
        register(AuthEventType.LOGIN_FAILED);

        verify(sessionTokenService, never()).revokeAllForStaff(any());
    }

    @Test
    void bothGatesShareOneCounter() {
        register(AuthEventType.LOGIN_FAILED);
        register(AuthEventType.MFA_FAILED);
        boolean locked = register(AuthEventType.LOGIN_FAILED);

        // Alternating between the password and the second factor buys no extra
        // attempts.
        assertThat(locked).isTrue();
    }

    @Test
    void locksTheRowItIsAboutToIncrement() {
        register(AuthEventType.LOGIN_FAILED);

        // A plain read would let parallel attempts overwrite each other's count.
        verify(staffRepository).findByIdForUpdate(staff.getId());
        verify(staffRepository, never()).findById(any(UUID.class));
    }

    @Test
    void stillRecordsTheAttemptWhenTheAccountHasVanished() {
        UUID unknown = UUID.randomUUID();
        when(staffRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        boolean locked = tracker.registerFailure(unknown, "bacsi", CONTEXT,
                AuthEventType.LOGIN_FAILED, "detail");

        assertThat(locked).isFalse();
        verify(authEventLogger).failureInCurrentTransaction(eq(AuthEventType.LOGIN_FAILED),
                eq("bacsi"), eq(unknown), any(), any());
        verify(staffRepository, never()).save(any());
    }
}
