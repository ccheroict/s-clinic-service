package com.sclinic.security;

import com.sclinic.security.authevent.AuthEventLogger;
import com.sclinic.security.authevent.AuthEventType;
import com.sclinic.security.authevent.RequestContext;
import com.sclinic.security.dto.ChangePasswordRequest;
import com.sclinic.security.dto.LoginRequest;
import com.sclinic.security.dto.LoginResponse;
import com.sclinic.security.mfa.MfaService;
import com.sclinic.security.password.PasswordPolicy;
import com.sclinic.security.session.SessionToken;
import com.sclinic.security.session.SessionTokenService;
import com.sclinic.security.session.TokenScope;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");
    private static final String CORRECT_PASSWORD = "ChungKham2026!";
    private static final RequestContext CONTEXT = new RequestContext("10.0.0.5", "JUnit");

    private StaffRepository staffRepository;
    private SessionTokenService sessionTokenService;
    private PasswordPolicy passwordPolicy;
    private MfaService mfaService;
    private LoginAttemptTracker loginAttemptTracker;
    private AuthEventLogger authEventLogger;
    private PasswordEncoder encoder;
    private AuthService service;

    private Staff staff;

    @BeforeEach
    void setUp() {
        staffRepository = mock(StaffRepository.class);
        sessionTokenService = mock(SessionTokenService.class);
        passwordPolicy = mock(PasswordPolicy.class);
        authEventLogger = mock(AuthEventLogger.class);
        encoder = new BCryptPasswordEncoder(4);

        mfaService = mock(MfaService.class);
        loginAttemptTracker = mock(LoginAttemptTracker.class);

        // MFA off in this class: these tests cover the credential gate. The MFA
        // gate and the lockout counter each have their own test class.
        AuthProperties properties = new AuthProperties(
                Duration.ofHours(8), Duration.ofMinutes(10), 3, Duration.ofMinutes(15), 5, 12,
                Set.of(), 1, 10, "S-Clinic");

        service = new AuthService(staffRepository, sessionTokenService, passwordPolicy, mfaService,
                loginAttemptTracker, authEventLogger, encoder, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUsername("bacsi");
        staff.setPasswordHash(encoder.encode(CORRECT_PASSWORD));
        staff.setFullName("BS Tran Thi B");
        staff.setRole("DOCTOR");
        staff.setActive(true);
    }

    private void staffExists() {
        when(staffRepository.findByUsernameAndActiveTrue("bacsi")).thenReturn(Optional.of(staff));
    }

    private void tokenIssued(TokenScope scope) {
        when(sessionTokenService.issue(any(Staff.class), eq(scope), any()))
                .thenReturn(new SessionTokenService.IssuedToken(
                        "raw-token-value", scope, NOW.plus(Duration.ofHours(8))));
    }

    @Nested
    class LoginTests {

        @Test
        void issuesFullScopeTokenOnCorrectCredentials() {
            staffExists();
            tokenIssued(TokenScope.FULL);

            LoginResponse response = service.login(
                    new LoginRequest("bacsi", CORRECT_PASSWORD), CONTEXT);

            assertThat(response.token()).isEqualTo("raw-token-value");
            assertThat(response.scope()).isEqualTo(TokenScope.FULL);
            assertThat(response.username()).isEqualTo("bacsi");
            assertThat(response.role()).isEqualTo("DOCTOR");
            assertThat(response.passwordChangeRequired()).isFalse();
            verify(authEventLogger).success(eq(AuthEventType.LOGIN_SUCCESS), eq("bacsi"),
                    eq(staff.getId()), any());
        }

        @Test
        void issuesChangePasswordScopeWhenRotationRequired() {
            staff.setMustChangePassword(true);
            staffExists();
            tokenIssued(TokenScope.CHANGE_PASSWORD);

            LoginResponse response = service.login(
                    new LoginRequest("bacsi", CORRECT_PASSWORD), CONTEXT);

            assertThat(response.scope()).isEqualTo(TokenScope.CHANGE_PASSWORD);
            assertThat(response.passwordChangeRequired()).isTrue();
        }

        @Test
        void rejectsUnknownAccountWithUniformMessage() {
            when(staffRepository.findByUsernameAndActiveTrue("khong-ton-tai"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login(
                    new LoginRequest("khong-ton-tai", "whatever"), CONTEXT))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .hasMessage("Invalid username or password");

            verify(authEventLogger).failure(eq(AuthEventType.LOGIN_UNKNOWN_ACCOUNT),
                    eq("khong-ton-tai"), eq(null), any(), any());
        }

        @Test
        void wrongPasswordGivesSameMessageAsUnknownAccount() {
            staffExists();

            assertThatThrownBy(() -> service.login(
                    new LoginRequest("bacsi", "wrong-password"), CONTEXT))
                    .isInstanceOf(AuthenticationFailedException.class)
                    .hasMessage("Invalid username or password");
        }

        @Test
        void handsAFailedAttemptToTheTracker() {
            staffExists();

            assertThatThrownBy(() -> service.login(new LoginRequest("bacsi", "wrong"), CONTEXT))
                    .isInstanceOf(AuthenticationFailedException.class);

            // The counter lives in its own transaction; see LoginAttemptTracker.
            verify(loginAttemptTracker).registerFailure(eq(staff.getId()), eq("bacsi"), any(),
                    eq(AuthEventType.LOGIN_FAILED), any());
            verify(sessionTokenService, never()).issue(any(), any(), any());
        }

        @Test
        void refusesLockedAccountWithoutCheckingPassword() {
            staffExists();
            staff.setLockedUntil(NOW.plus(Duration.ofMinutes(5)));

            assertThatThrownBy(() -> service.login(
                    new LoginRequest("bacsi", CORRECT_PASSWORD), CONTEXT))
                    .isInstanceOf(AuthenticationFailedException.class);

            verify(authEventLogger).failure(eq(AuthEventType.LOGIN_BLOCKED), eq("bacsi"),
                    eq(staff.getId()), any(), any());
            verify(sessionTokenService, never()).issue(any(), any(), any());
        }

        @Test
        void acceptsLoginOnceLockHasExpired() {
            staffExists();
            staff.setLockedUntil(NOW.minus(Duration.ofSeconds(1)));
            tokenIssued(TokenScope.FULL);

            LoginResponse response = service.login(
                    new LoginRequest("bacsi", CORRECT_PASSWORD), CONTEXT);

            assertThat(response.scope()).isEqualTo(TokenScope.FULL);
            assertThat(staff.getLockedUntil()).isNull();
        }

        @Test
        void resetsFailureCounterOnSuccess() {
            staffExists();
            staff.setFailedAttempts(2);
            tokenIssued(TokenScope.FULL);

            service.login(new LoginRequest("bacsi", CORRECT_PASSWORD), CONTEXT);

            assertThat(staff.getFailedAttempts()).isZero();
        }
    }

    @Nested
    class ChangePasswordTests {

        private SessionToken tokenWithScope(TokenScope scope) {
            SessionToken token = new SessionToken();
            token.setId(UUID.randomUUID());
            token.setStaffId(staff.getId());
            token.setScope(scope);
            token.setExpiresAt(NOW.plus(Duration.ofHours(1)));
            return token;
        }

        @Test
        void changesPasswordAndReturnsFullSession() {
            when(sessionTokenService.find("interim"))
                    .thenReturn(Optional.of(tokenWithScope(TokenScope.CHANGE_PASSWORD)));
            when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
            tokenIssued(TokenScope.FULL);

            LoginResponse response = service.changePassword("interim",
                    new ChangePasswordRequest(CORRECT_PASSWORD, "MoiHoanToan2027!"), CONTEXT);

            assertThat(response.scope()).isEqualTo(TokenScope.FULL);
            assertThat(response.passwordChangeRequired()).isFalse();
            assertThat(staff.isMustChangePassword()).isFalse();
            assertThat(staff.getPasswordChangedAt()).isEqualTo(NOW);
            assertThat(encoder.matches("MoiHoanToan2027!", staff.getPasswordHash())).isTrue();
        }

        @Test
        void revokesAllOtherSessionsAfterChange() {
            when(sessionTokenService.find("full"))
                    .thenReturn(Optional.of(tokenWithScope(TokenScope.FULL)));
            when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
            tokenIssued(TokenScope.FULL);

            service.changePassword("full",
                    new ChangePasswordRequest(CORRECT_PASSWORD, "MoiHoanToan2027!"), CONTEXT);

            verify(sessionTokenService).revokeAllForStaff(staff.getId());
        }

        @Test
        void recordsPreviousHashSoItCannotBeReused() {
            String originalHash = staff.getPasswordHash();
            when(sessionTokenService.find("full"))
                    .thenReturn(Optional.of(tokenWithScope(TokenScope.FULL)));
            when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
            tokenIssued(TokenScope.FULL);

            service.changePassword("full",
                    new ChangePasswordRequest(CORRECT_PASSWORD, "MoiHoanToan2027!"), CONTEXT);

            verify(passwordPolicy).remember(staff.getId(), originalHash);
        }

        @Test
        void requiresCorrectCurrentPassword() {
            when(sessionTokenService.find("full"))
                    .thenReturn(Optional.of(tokenWithScope(TokenScope.FULL)));
            when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));

            assertThatThrownBy(() -> service.changePassword("full",
                    new ChangePasswordRequest("wrong-current", "MoiHoanToan2027!"), CONTEXT))
                    .isInstanceOf(AuthenticationFailedException.class);

            verify(authEventLogger).failure(eq(AuthEventType.PASSWORD_CHANGE_REJECTED),
                    eq("bacsi"), eq(staff.getId()), any(), any());
        }

        @Test
        void rejectsInterimTokenOfTheWrongScope() {
            when(sessionTokenService.find("mfa"))
                    .thenReturn(Optional.of(tokenWithScope(TokenScope.MFA_PENDING)));

            assertThatThrownBy(() -> service.changePassword("mfa",
                    new ChangePasswordRequest(CORRECT_PASSWORD, "MoiHoanToan2027!"), CONTEXT))
                    .isInstanceOf(AuthenticationFailedException.class);
        }

        @Test
        void rejectsUnknownToken() {
            when(sessionTokenService.find("nonsense")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.changePassword("nonsense",
                    new ChangePasswordRequest(CORRECT_PASSWORD, "MoiHoanToan2027!"), CONTEXT))
                    .isInstanceOf(AuthenticationFailedException.class);
        }
    }

    @Nested
    class LogoutTests {

        @Test
        void revokesTokenAndRecordsEvent() {
            SessionToken token = new SessionToken();
            token.setStaffId(staff.getId());
            token.setScope(TokenScope.FULL);
            token.setExpiresAt(NOW.plus(Duration.ofHours(1)));

            when(sessionTokenService.find("raw")).thenReturn(Optional.of(token));
            when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));

            service.logout("raw", CONTEXT);

            verify(sessionTokenService).revoke("raw");
            verify(authEventLogger).success(eq(AuthEventType.LOGOUT), eq("bacsi"),
                    eq(staff.getId()), any());
        }

        @Test
        void toleratesLogoutWithAnAlreadyInvalidToken() {
            when(sessionTokenService.find("stale")).thenReturn(Optional.empty());

            service.logout("stale", CONTEXT);

            verify(sessionTokenService).revoke("stale");
            verify(authEventLogger).success(eq(AuthEventType.LOGOUT), eq(""), eq(null), any());
        }
    }
}
