package com.sclinic.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclinic.security.authevent.AuthEventRepository;
import com.sclinic.security.authevent.AuthEventType;
import com.sclinic.security.dto.LoginRequest;
import com.sclinic.security.session.SessionTokenRepository;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import com.sclinic.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The lockout, against a real database and with no test transaction.
 *
 * <p>The missing test transaction is the whole point. A rejected login throws,
 * which rolls back the transaction that raised it; anything written on the way
 * out has to be committed separately or it disappears. A test that wraps the
 * whole flow in one rollback-at-the-end transaction cannot tell the difference,
 * which is exactly how a lockout that never locked, and failure records that
 * were never kept, went unnoticed.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-username=admin",
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-LOCK-001",
        "sclinic.auth.mfa-required-roles=",
        "sclinic.auth.max-failed-attempts=3",
        "sclinic.auth.lockout-duration=15m",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
class AccountLockoutIntegrationTest {

    private static final String ADMIN_PASSWORD = "AdminKham2026!Test";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrlFor("lockout_it"));
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    AuthEventRepository authEventRepository;

    @Autowired
    SessionTokenRepository sessionTokenRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetAdminAccount() {
        Staff admin = admin();
        admin.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
        admin.setMustChangePassword(false);
        admin.setFailedAttempts(0);
        admin.setLockedUntil(null);
        staffRepository.save(admin);

        authEventRepository.deleteAll();
        sessionTokenRepository.deleteAll();
    }

    private Staff admin() {
        return staffRepository.findByUsernameAndActiveTrue("admin").orElseThrow();
    }

    private void attemptLogin(String password, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("admin", password))))
                .andExpect(status().is(expectedStatus));
    }

    private JsonNode loginSuccessfully() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("admin", ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void aFailedAttemptIsCountedDurably() throws Exception {
        attemptLogin("wrong-password", 401);

        assertThat(admin().getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void attemptsAccumulateAcrossRequests() throws Exception {
        attemptLogin("wrong-password", 401);
        attemptLogin("wrong-password", 401);

        assertThat(admin().getFailedAttempts()).isEqualTo(2);
        assertThat(admin().getLockedUntil()).isNull();
    }

    @Test
    void theAccountLocksOnTheConfiguredAttempt() throws Exception {
        attemptLogin("wrong-password", 401);
        attemptLogin("wrong-password", 401);
        attemptLogin("wrong-password", 401);

        Staff locked = admin();
        assertThat(locked.isLockedAt(Instant.now())).isTrue();
        assertThat(locked.getFailedAttempts()).isZero();
    }

    @Test
    void aLockedAccountRefusesEvenTheCorrectPassword() throws Exception {
        for (int i = 0; i < 3; i++) {
            attemptLogin("wrong-password", 401);
        }

        attemptLogin(ADMIN_PASSWORD, 401);
    }

    @Test
    void aSuccessfulLoginClearsTheCounter() throws Exception {
        attemptLogin("wrong-password", 401);
        attemptLogin("wrong-password", 401);

        loginSuccessfully();

        assertThat(admin().getFailedAttempts()).isZero();
    }

    @Test
    void lockingDropsAnyLiveSession() throws Exception {
        loginSuccessfully();
        assertThat(sessionTokenRepository.countByStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                admin().getId(), Instant.now())).isEqualTo(1);

        for (int i = 0; i < 3; i++) {
            attemptLogin("wrong-password", 401);
        }

        assertThat(sessionTokenRepository.countByStaffIdAndRevokedAtIsNullAndExpiresAtAfter(
                admin().getId(), Instant.now())).isZero();
    }

    @Test
    void rejectedAttemptsAreKeptInTheAuthTrail() throws Exception {
        attemptLogin("wrong-password", 401);

        assertThat(authEventRepository.findByEventTypeOrderByCreatedAtDesc(
                AuthEventType.LOGIN_FAILED.name())).hasSize(1);
    }

    @Test
    void theLockItselfIsRecorded() throws Exception {
        for (int i = 0; i < 3; i++) {
            attemptLogin("wrong-password", 401);
        }

        assertThat(authEventRepository.findByEventTypeOrderByCreatedAtDesc(
                AuthEventType.ACCOUNT_LOCKED.name())).hasSize(1);
        assertThat(authEventRepository.findByEventTypeOrderByCreatedAtDesc(
                AuthEventType.LOGIN_BLOCKED.name())).isEmpty();
    }

    @Test
    void anAttemptOnALockedAccountIsRecordedAsBlocked() throws Exception {
        for (int i = 0; i < 3; i++) {
            attemptLogin("wrong-password", 401);
        }

        attemptLogin(ADMIN_PASSWORD, 401);

        assertThat(authEventRepository.findByEventTypeOrderByCreatedAtDesc(
                AuthEventType.LOGIN_BLOCKED.name())).hasSize(1);
    }

    /**
     * The limit has to hold against a parallel attacker, not just a sequential
     * one. Counting the failures without locking the row lets simultaneous
     * attempts overwrite each other's count, so the number of guesses admitted
     * before the lock engages grows with the attacker's concurrency. That is the
     * kind of defect a sequential test cannot see.
     */
    @Test
    void theLimitHoldsWhenAttemptsArriveInParallel() throws Exception {
        int parallelAttempts = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        try {
            List<Future<?>> attempts = new ArrayList<>();
            for (int i = 0; i < parallelAttempts; i++) {
                attempts.add(pool.submit(() -> {
                    startTogether.await();
                    mockMvc.perform(post("/api/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(
                                            new LoginRequest("admin", "wrong-password"))))
                            .andExpect(status().isUnauthorized());
                    completed.incrementAndGet();
                    return null;
                }));
            }

            startTogether.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(completed).hasValue(parallelAttempts);
        assertThat(admin().isLockedAt(Instant.now())).isTrue();

        int blocked = authEventRepository.findByEventTypeOrderByCreatedAtDesc(
                AuthEventType.LOGIN_BLOCKED.name()).size();
        int failed = authEventRepository.findByEventTypeOrderByCreatedAtDesc(
                AuthEventType.LOGIN_FAILED.name()).size();

        // Every attempt is accounted for exactly once, which is what breaks when
        // the counter is a read-modify-write without a lock: increments are lost,
        // the threshold is never reached, and nothing is ever blocked.
        assertThat(failed + blocked).isEqualTo(parallelAttempts);

        // Most attempts never reach a password comparison at all. The bound is
        // "most" rather than "all but three" on purpose: the lock check and the
        // comparison happen before the counter is touched, so requests already in
        // flight when the account locks still get compared. That window is bounded
        // by how many requests the server handles at once, not by how many the
        // attacker sends -- with the lost update it was unbounded, and the account
        // never locked at all.
        assertThat(blocked)
                .as("attempts refused by the lock rather than compared")
                .isGreaterThanOrEqualTo(parallelAttempts / 2);
    }

    @Test
    void anAttemptOnAnUnknownAccountIsRecordedToo() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("khong-ton-tai", "whatever"))))
                .andExpect(status().isUnauthorized());

        assertThat(authEventRepository.findByEventTypeOrderByCreatedAtDesc(
                AuthEventType.LOGIN_UNKNOWN_ACCOUNT.name())).hasSize(1);
    }
}
