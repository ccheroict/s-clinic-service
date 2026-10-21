package com.sclinic.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclinic.security.dto.ChangePasswordRequest;
import com.sclinic.security.dto.LoginRequest;
import com.sclinic.security.dto.MfaCodeRequest;
import com.sclinic.security.mfa.StaffBackupCodeRepository;
import com.sclinic.security.mfa.TotpGenerator;
import com.sclinic.security.password.StaffPasswordHistoryRepository;
import com.sclinic.security.session.SessionTokenRepository;
import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import com.sclinic.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The second-factor gate end to end, against a real database and real TOTP codes.
 *
 * <p>The seeded admin has role ADMIN, which this class configures as MFA-required,
 * so it exercises the full ordering: password, then second factor, then password
 * rotation, then a usable session.
 *
 * <p>Deliberately not {@code @Transactional}. These flows span several requests
 * and some of them end in an exception that the service raises after writing
 * (a failed attempt counter), which would mark a shared test transaction
 * rollback-only. State is reset explicitly before each test instead.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-username=admin",
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-MFA-001",
        "sclinic.auth.mfa-required-roles=ADMIN",
        "sclinic.auth.max-failed-attempts=3",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
class MfaIntegrationTest {

    private static final String ADMIN_PASSWORD = "AdminKham2026!Test";
    private static final String ROTATED_PASSWORD = "MatKhauMoi2027!";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrlFor("mfa_it"));
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
    StaffBackupCodeRepository backupCodeRepository;

    @Autowired
    StaffPasswordHistoryRepository passwordHistoryRepository;

    @Autowired
    SessionTokenRepository sessionTokenRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    com.sclinic.security.mfa.MfaService mfaService;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** Returns the admin to the state the seeder left it in. */
    @BeforeEach
    void resetAdminAccount() {
        Staff admin = staffRepository.findByUsernameAndActiveTrue("admin").orElseThrow();
        admin.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
        admin.setPasswordChangedAt(null);
        admin.setMustChangePassword(true);
        admin.setFailedAttempts(0);
        admin.setLockedUntil(null);
        admin.setTotpSecret(null);
        admin.setTotpEnabled(false);
        admin.setTotpConfirmedAt(null);
        // Cleared too, or each test would inherit the previous test's replay
        // high-water mark and the class would only pass in the order it happens
        // to run in.
        admin.setTotpLastStep(null);
        staffRepository.save(admin);

        backupCodeRepository.deleteAll();
        passwordHistoryRepository.deleteAll();
        sessionTokenRepository.deleteAll();
    }

    // ---------- helpers ----------

    private JsonNode login(String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode enroll(String token) throws Exception {
        String body = mockMvc.perform(post("/api/auth/mfa/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private ResultActions submitCode(String path, String token, String code) throws Exception {
        return mockMvc.perform(post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new MfaCodeRequest(code))));
    }

    private String currentCode(String secret) {
        return TotpGenerator.codeAt(secret, Instant.now());
    }

    /**
     * A valid code from the <em>next</em> time step.
     *
     * <p>Needed because codes are now single-use. Enrolment consumes the current
     * step's code, so a test that enrols and then logs in within the same thirty
     * seconds cannot present the same digits again — the server is right to
     * refuse them, and {@code theCodeUsedToCompleteEnrolmentCannotThenLogIn}
     * asserts exactly that. The next step's code is inside the drift window, so
     * it is accepted, and its step is higher, so it is not a replay. Sleeping for
     * thirty seconds would be the alternative.
     *
     * <p>This is also what the behaviour costs in real use: someone who logs in
     * twice inside the same thirty-second window waits for the next code. Normal
     * for TOTP, and cheap next to a code that stays usable for ninety seconds
     * after anyone sees it.
     */
    private String nextStepCode(String secret) {
        return TotpGenerator.codeAt(
                secret, Instant.now().plusSeconds(TotpGenerator.TIME_STEP_SECONDS));
    }

    /** A six-digit code that is invalid now even allowing for clock drift. */
    private String wrongCode(String secret) {
        for (int candidate = 0; candidate < 1000; candidate++) {
            String code = String.format("%06d", candidate);
            if (!TotpGenerator.verify(secret, code, Instant.now(), 3)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not find an invalid code");
    }

    /**
     * Walks a fresh admin all the way through: enrol the second factor, rotate the
     * seeded password, and end up with a usable session.
     */
    private Enrolled enrolAndRotate() throws Exception {
        String enrolToken = login(ADMIN_PASSWORD).get("token").asText();
        String secret = enroll(enrolToken).get("secret").asText();

        // Kept, because confirming enrolment consumes this code and a test needs
        // to show it cannot then be replayed as a login.
        String enrolmentCode = currentCode(secret);

        String confirmBody = submitCode("/api/auth/mfa/confirm", enrolToken, enrolmentCode)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode confirmed = objectMapper.readTree(confirmBody);

        List<String> backupCodes = new ArrayList<>();
        confirmed.get("backupCodes").forEach(node -> backupCodes.add(node.asText()));

        String changePasswordToken = confirmed.get("session").get("token").asText();
        String rotatedBody = mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + changePasswordToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest(ADMIN_PASSWORD, ROTATED_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String fullToken = objectMapper.readTree(rotatedBody).get("token").asText();
        return new Enrolled(secret, backupCodes, fullToken, enrolmentCode);
    }

    private record Enrolled(String secret, List<String> backupCodes, String fullToken,
                            String enrolmentCode) {
    }

    private Staff admin() {
        return staffRepository.findByUsernameAndActiveTrue("admin").orElseThrow();
    }

    // ---------- enrolment ----------

    @Test
    void anMfaRoleWithoutASecondFactorIsSentToEnrolment() throws Exception {
        JsonNode response = login(ADMIN_PASSWORD);

        assertThat(response.get("scope").asText()).isEqualTo("ENROLL_MFA");
        // The password rotation is still pending but is not the current step.
        assertThat(response.get("passwordChangeRequired").asBoolean()).isFalse();
    }

    @Test
    void anEnrolmentTokenReachesNoBusinessData() throws Exception {
        String token = login(ADMIN_PASSWORD).get("token").asText();

        mockMvc.perform(get("/api/facility").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anEnrolmentTokenCannotSkipAheadToThePasswordChange() throws Exception {
        String token = login(ADMIN_PASSWORD).get("token").asText();

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest(ADMIN_PASSWORD, ROTATED_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void enrolmentReturnsASecretAndAProvisioningUri() throws Exception {
        String token = login(ADMIN_PASSWORD).get("token").asText();

        JsonNode enrolment = enroll(token);

        String secret = enrolment.get("secret").asText();
        assertThat(secret).hasSize(32); // 20 random bytes, base32, no padding
        assertThat(enrolment.get("provisioningUri").asText())
                .startsWith("otpauth://totp/S-Clinic:admin?secret=" + secret)
                .contains("issuer=S-Clinic")
                .contains("period=30");
    }

    @Test
    void anUnconfirmedEnrolmentDoesNotCountAsASecondFactor() throws Exception {
        String token = login(ADMIN_PASSWORD).get("token").asText();
        enroll(token);

        assertThat(admin().isTotpEnabled()).isFalse();
        // Logging in again still lands on enrolment, not on a challenge.
        assertThat(login(ADMIN_PASSWORD).get("scope").asText()).isEqualTo("ENROLL_MFA");
    }

    @Test
    void confirmRejectsAWrongCodeAndLeavesTheFactorDisabled() throws Exception {
        String token = login(ADMIN_PASSWORD).get("token").asText();
        String secret = enroll(token).get("secret").asText();

        submitCode("/api/auth/mfa/confirm", token, wrongCode(secret))
                .andExpect(status().isUnauthorized());

        assertThat(admin().isTotpEnabled()).isFalse();
    }

    @Test
    void confirmIssuesRecoveryCodesAndTheNextGate() throws Exception {
        String enrolToken = login(ADMIN_PASSWORD).get("token").asText();
        String secret = enroll(enrolToken).get("secret").asText();

        submitCode("/api/auth/mfa/confirm", enrolToken, currentCode(secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backupCodes.length()").value(10))
                // Enrolment cleared the MFA gate; the seeded rotation is next.
                .andExpect(jsonPath("$.session.scope").value("CHANGE_PASSWORD"))
                .andExpect(jsonPath("$.session.passwordChangeRequired").value(true));

        assertThat(admin().isTotpEnabled()).isTrue();
        assertThat(admin().getTotpConfirmedAt()).isNotNull();
        assertThat(backupCodeRepository.countByStaffIdAndUsedAtIsNull(admin().getId())).isEqualTo(10);
    }

    @Test
    void theEnrolmentTokenIsSpentOnceEnrolmentCompletes() throws Exception {
        String enrolToken = login(ADMIN_PASSWORD).get("token").asText();
        String secret = enroll(enrolToken).get("secret").asText();

        submitCode("/api/auth/mfa/confirm", enrolToken, currentCode(secret))
                .andExpect(status().isOk());

        submitCode("/api/auth/mfa/confirm", enrolToken, currentCode(secret))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A stolen full session must not be able to swap the second factor out. If it
     * could, the factor would be worth nothing against session theft, and keeping
     * the admin reset endpoint admin-only would be pointless: enroll plus confirm
     * achieves the same thing without an administrator.
     */
    @Test
    void aFullSessionCannotReplaceAnExistingSecondFactor() throws Exception {
        Enrolled enrolled = enrolAndRotate();

        mockMvc.perform(post("/api/auth/mfa/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + enrolled.fullToken()))
                .andExpect(status().isConflict());

        // The original factor is untouched, so the original codes still work.
        assertThat(admin().isTotpEnabled()).isTrue();
        assertThat(admin().getTotpSecret()).isNotNull();

        String pending = login(ROTATED_PASSWORD).get("token").asText();
        submitCode("/api/auth/mfa/verify", pending, nextStepCode(enrolled.secret()))
                .andExpect(status().isOk());
    }

    /**
     * Opening the setup screen and walking away used to disable a working factor,
     * because issuing a new secret cleared the old one and its recovery codes. That
     * is a lockout caused by an action that looked harmless.
     */
    @Test
    void anAbandonedEnrolmentLeavesAWorkingFactorAlone() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        long codesBefore = backupCodeRepository.countByStaffIdAndUsedAtIsNull(admin().getId());

        mockMvc.perform(post("/api/auth/mfa/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + enrolled.fullToken()))
                .andExpect(status().isConflict());

        // Next login is a challenge, not a fresh enrolment.
        assertThat(login(ROTATED_PASSWORD).get("scope").asText()).isEqualTo("MFA_PENDING");
        assertThat(backupCodeRepository.countByStaffIdAndUsedAtIsNull(admin().getId()))
                .isEqualTo(codesBefore);
    }

    @Test
    void aCompletedEnrolmentEndsInAUsableSession() throws Exception {
        Enrolled enrolled = enrolAndRotate();

        mockMvc.perform(get("/api/facility")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + enrolled.fullToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kcbCode").value("KCB-MFA-001"));
    }

    // ---------- login challenge ----------

    @Test
    void aLaterLoginIsChallengedForTheSecondFactor() throws Exception {
        enrolAndRotate();

        JsonNode response = login(ROTATED_PASSWORD);

        assertThat(response.get("scope").asText()).isEqualTo("MFA_PENDING");
        // A correct password alone now buys nothing.
        mockMvc.perform(get("/api/facility")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + response.get("token").asText()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void answeringTheChallengeGrantsAFullSession() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        String pending = login(ROTATED_PASSWORD).get("token").asText();

        String body = submitCode("/api/auth/mfa/verify", pending, nextStepCode(enrolled.secret()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("FULL"))
                .andReturn().getResponse().getContentAsString();

        String full = objectMapper.readTree(body).get("token").asText();
        mockMvc.perform(get("/api/facility").header(HttpHeaders.AUTHORIZATION, "Bearer " + full))
                .andExpect(status().isOk());
    }

    @Test
    void thePendingTokenIsSpentOnceTheChallengeIsAnswered() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        String pending = login(ROTATED_PASSWORD).get("token").asText();

        submitCode("/api/auth/mfa/verify", pending, nextStepCode(enrolled.secret()))
                .andExpect(status().isOk());

        submitCode("/api/auth/mfa/verify", pending, nextStepCode(enrolled.secret()))
                .andExpect(status().isUnauthorized());
    }

    // ---------- single use of a TOTP code (RFC 6238 section 5.2) ----------

    @Test
    void aCodeThatHasBeenUsedOnceIsRefusedTheSecondTime() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        // Next step, because enrolment already consumed this step's code.
        String code = nextStepCode(enrolled.secret());

        String firstPending = login(ROTATED_PASSWORD).get("token").asText();
        submitCode("/api/auth/mfa/verify", firstPending, code).andExpect(status().isOk());

        // A fresh login, so the interim token is not the thing being spent. The
        // same six digits are still inside the drift window and were accepted a
        // moment ago; before this they stayed usable for the whole window, so
        // anyone who saw them once — over a shoulder, in a phishing form, in a
        // proxy log — could log in with them.
        String secondPending = login(ROTATED_PASSWORD).get("token").asText();
        submitCode("/api/auth/mfa/verify", secondPending, code)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theReplayIsReportedAsAnInvalidCodeAndNotAsAnAlreadyUsedOne() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        String code = nextStepCode(enrolled.secret());

        String firstPending = login(ROTATED_PASSWORD).get("token").asText();
        submitCode("/api/auth/mfa/verify", firstPending, code).andExpect(status().isOk());

        String secondPending = login(ROTATED_PASSWORD).get("token").asText();
        String replayBody = submitCode("/api/auth/mfa/verify", secondPending, code)
                .andReturn().getResponse().getContentAsString();

        String wrongBody = submitCode("/api/auth/mfa/verify",
                login(ROTATED_PASSWORD).get("token").asText(), wrongCode(enrolled.secret()))
                .andReturn().getResponse().getContentAsString();

        // Identical answers on purpose. "That code was already used" tells
        // someone holding a stolen code that they have the right code and only
        // arrived late, which is precisely what they want to learn.
        assertThat(replayBody).isEqualTo(wrongBody);
    }

    @Test
    void theStepOfTheUsedCodeIsRecordedOnTheAccount() throws Exception {
        Enrolled enrolled = enrolAndRotate();

        // Enrolment itself consumes a code, so a step is already recorded.
        Long afterEnrolment = admin().getTotpLastStep();
        assertThat(afterEnrolment).isNotNull();

        String pending = login(ROTATED_PASSWORD).get("token").asText();
        submitCode("/api/auth/mfa/verify", pending, nextStepCode(enrolled.secret()))
                .andExpect(status().isOk());

        // One number, not a table of used codes, and it moves forward. Steps only
        // ever increase, so a high-water mark answers the only question asked —
        // and a table of authentication attempts would need pruning and would
        // otherwise become a growing record of exactly when each member of staff
        // was at work.
        assertThat(admin().getTotpLastStep()).isGreaterThan(afterEnrolment);
    }

    @Test
    void theCodeUsedToCompleteEnrolmentCannotThenLogIn() throws Exception {
        // Enrolment and verification share the claim, so the code that proved the
        // authenticator works cannot immediately be replayed as a login. Without
        // this the first ninety seconds after enrolment had a reusable code in
        // them, which is the window a shoulder-surfer gets.
        Enrolled enrolled = enrolAndRotate();
        String enrolmentCode = enrolled.enrolmentCode();

        String pending = login(ROTATED_PASSWORD).get("token").asText();
        submitCode("/api/auth/mfa/verify", pending, enrolmentCode)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aResetClearsTheReplayMarkSoTheNextEnrolmentIsNotBlocked() throws Exception {
        enrolAndRotate();
        assertThat(admin().getTotpLastStep()).isNotNull();

        Staff reset = admin();
        mfaService.reset(reset);

        // A new secret produces unrelated codes, so keeping the old high-water
        // mark would only reject the first valid code of the new factor whenever
        // re-enrolment landed on an earlier step than the last login.
        assertThat(admin().getTotpLastStep()).isNull();
    }

    // ---------- reissuing recovery codes (task 1.9c, batch C) ----------

    @Test
    void theAccountHolderCanReplaceTheirRecoveryCodes() throws Exception {
        Enrolled enrolled = enrolAndRotate();

        // The codes are shown once at enrolment. Reloading that page used to lose
        // them for good, leaving an administrator clearing the whole second
        // factor as the only way back — a heavier operation than the problem, and
        // one that leaves the account with no second factor at all for a while.
        String body = submitCode("/api/auth/mfa/backup-codes", enrolled.fullToken(),
                        nextStepCode(enrolled.secret()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode codes = objectMapper.readTree(body).get("backupCodes");
        assertThat(codes).hasSize(10);
    }

    @Test
    void reissuingInvalidatesTheOldRecoveryCodes() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        String oldCode = enrolled.backupCodes().get(0);

        submitCode("/api/auth/mfa/backup-codes", enrolled.fullToken(),
                nextStepCode(enrolled.secret()))
                .andExpect(status().isOk());

        // Otherwise reissuing would add ways past the second factor rather than
        // replace them, and a set of codes written down and lost would stay live
        // for ever.
        String pending = login(ROTATED_PASSWORD).get("token").asText();
        submitCode("/api/auth/mfa/verify", pending, oldCode)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theNewRecoveryCodesWork() throws Exception {
        Enrolled enrolled = enrolAndRotate();

        String body = submitCode("/api/auth/mfa/backup-codes", enrolled.fullToken(),
                        nextStepCode(enrolled.secret()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String freshCode = objectMapper.readTree(body).get("backupCodes").get(0).asText();

        String pending = login(ROTATED_PASSWORD).get("token").asText();
        submitCode("/api/auth/mfa/verify", pending, freshCode).andExpect(status().isOk());
    }

    @Test
    void aFullSessionAloneCannotMintRecoveryCodes() throws Exception {
        Enrolled enrolled = enrolAndRotate();

        // The point of requiring a code. Recovery codes outlive the session that
        // made them and each one bypasses the second factor, so a stolen session
        // that could mint them would convert temporary access into permanent
        // second-factor bypass.
        submitCode("/api/auth/mfa/backup-codes", enrolled.fullToken(),
                wrongCode(enrolled.secret()))
                .andExpect(status().isUnauthorized());

        // And the old codes are untouched by the failed attempt.
        String pending = login(ROTATED_PASSWORD).get("token").asText();
        submitCode("/api/auth/mfa/verify", pending, enrolled.backupCodes().get(0))
                .andExpect(status().isOk());
    }

    @Test
    void anInterimTokenCannotReissueRecoveryCodes() throws Exception {
        String enrolToken = login(ADMIN_PASSWORD).get("token").asText();

        // A half-finished login has not proved enough to be handed a set of
        // permanent bypasses.
        submitCode("/api/auth/mfa/backup-codes", enrolToken, "123456")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reissuingIsRecordedAsItsOwnEvent() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        // A delta, because auth_event accumulates across the tests in this class
        // and is not cleared between them.
        long before = reissueEvents();

        submitCode("/api/auth/mfa/backup-codes", enrolled.fullToken(),
                nextStepCode(enrolled.secret()))
                .andExpect(status().isOk());

        // Not folded into MFA_ENROLLED: this hands out fresh ways past the second
        // factor without the factor changing, and a review asking when an account
        // last gained a bypass should not have to infer it from an enrolment that
        // did not happen.
        assertThat(reissueEvents() - before).isEqualTo(1);
    }

    private long reissueEvents() {
        return jdbcTemplate.queryForObject("""
                select count(*) from auth_event
                where event_type = 'MFA_BACKUP_CODES_REISSUED' and succeeded = true
                """, Long.class);
    }

    // ---------- an expired pending login is not a wrong code ----------

    @Test
    void anExpiredPendingTokenSaysSoRatherThanBlamingTheCode() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        String pending = login(ROTATED_PASSWORD).get("token").asText();

        // The token is revoked rather than aged, which puts it in the same state
        // the ten-minute expiry would: real, and no longer usable.
        sessionTokenRepository.findAll().stream()
                .filter(token -> token.getScope()
                        == com.sclinic.security.session.TokenScope.MFA_PENDING)
                .forEach(token -> {
                    token.setRevokedAt(Instant.now());
                    sessionTokenRepository.save(token);
                });

        String body = submitCode("/api/auth/mfa/verify", pending, nextStepCode(enrolled.secret()))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Someone who leaves the code screen open over a break comes back to a
        // dead token. Telling them the code was invalid sends them to re-read a
        // code that was never the problem and eventually to decide the
        // authenticator app is broken.
        assertThat(objectMapper.readTree(body).get("reason").asText())
                .isEqualTo("SESSION_EXPIRED");
    }

    @Test
    void aGarbageTokenStaysTheUniformFailure() throws Exception {
        String body = submitCode("/api/auth/mfa/verify", "not-a-real-token", "123456")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Only a token that was actually issued is reported as expired. A value
        // that never existed says nothing at all.
        assertThat(body).doesNotContain("SESSION_EXPIRED");
    }

    @Test
    void aWrongCodeOnALiveTokenStillBlamesTheCode() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        String pending = login(ROTATED_PASSWORD).get("token").asText();

        String body = submitCode("/api/auth/mfa/verify", pending, wrongCode(enrolled.secret()))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("SESSION_EXPIRED");
    }

    @Test
    void aFullTokenCannotBeUsedToAnswerAChallenge() throws Exception {
        Enrolled enrolled = enrolAndRotate();

        submitCode("/api/auth/mfa/verify", enrolled.fullToken(), currentCode(enrolled.secret()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbageTokensAreRejectedByEveryMfaEndpoint() throws Exception {
        mockMvc.perform(post("/api/auth/mfa/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());

        submitCode("/api/auth/mfa/verify", "not-a-real-token", "123456")
                .andExpect(status().isUnauthorized());
        submitCode("/api/auth/mfa/confirm", "not-a-real-token", "123456")
                .andExpect(status().isUnauthorized());
    }

    // ---------- recovery codes ----------

    @Test
    void aRecoveryCodeAnswersTheChallengeButOnlyOnce() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        String recoveryCode = enrolled.backupCodes().get(0);

        String pending = login(ROTATED_PASSWORD).get("token").asText();
        submitCode("/api/auth/mfa/verify", pending, recoveryCode)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("FULL"));

        assertThat(backupCodeRepository.countByStaffIdAndUsedAtIsNull(admin().getId())).isEqualTo(9);

        String secondPending = login(ROTATED_PASSWORD).get("token").asText();
        submitCode("/api/auth/mfa/verify", secondPending, recoveryCode)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void recoveryCodesAreStoredHashedNotInTheClear() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        String recoveryCode = enrolled.backupCodes().get(0);

        boolean anyStoredInTheClear = backupCodeRepository.findAll().stream()
                .anyMatch(stored -> stored.getCodeHash().contains(recoveryCode));

        assertThat(anyStoredInTheClear).isFalse();
    }

    // ---------- brute force ----------

    @Test
    void wrongCodesCountTowardTheLockout() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        String pending = login(ROTATED_PASSWORD).get("token").asText();
        String wrong = wrongCode(enrolled.secret());

        // max-failed-attempts is 3 for this class.
        submitCode("/api/auth/mfa/verify", pending, wrong).andExpect(status().isUnauthorized());
        submitCode("/api/auth/mfa/verify", pending, wrong).andExpect(status().isUnauthorized());
        submitCode("/api/auth/mfa/verify", pending, wrong).andExpect(status().isUnauthorized());

        assertThat(admin().isLockedAt(Instant.now())).isTrue();

        // A locked account cannot start over with a correct password either.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("admin", ROTATED_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lockingAnAccountDropsItsLiveSessions() throws Exception {
        Enrolled enrolled = enrolAndRotate();
        String wrong = wrongCode(enrolled.secret());
        String pending = login(ROTATED_PASSWORD).get("token").asText();

        for (int i = 0; i < 3; i++) {
            submitCode("/api/auth/mfa/verify", pending, wrong).andExpect(status().isUnauthorized());
        }

        // The full session obtained earlier must not survive the lockout.
        mockMvc.perform(get("/api/facility")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + enrolled.fullToken()))
                .andExpect(status().isUnauthorized());
    }

    // ---------- admin reset ----------

    @Test
    void enrolmentIsPossibleAgainOnceAnAdminHasResetIt() throws Exception {
        Enrolled enrolled = enrolAndRotate();

        mockMvc.perform(post("/api/auth/mfa/reset/" + admin().getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + enrolled.fullToken()))
                .andExpect(status().isNoContent());

        // The admin reset is the supported route for a lost device, so it has to
        // actually reopen enrolment.
        String enrolToken = login(ROTATED_PASSWORD).get("token").asText();
        String secret = enroll(enrolToken).get("secret").asText();
        submitCode("/api/auth/mfa/confirm", enrolToken, currentCode(secret))
                .andExpect(status().isOk());

        assertThat(admin().isTotpEnabled()).isTrue();
    }

    @Test
    void anAdminCanClearASecondFactorAfterALostDevice() throws Exception {
        Enrolled enrolled = enrolAndRotate();

        mockMvc.perform(post("/api/auth/mfa/reset/" + admin().getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + enrolled.fullToken()))
                .andExpect(status().isNoContent());

        Staff after = admin();
        assertThat(after.isTotpEnabled()).isFalse();
        assertThat(after.getTotpSecret()).isNull();
        assertThat(backupCodeRepository.countByStaffIdAndUsedAtIsNull(after.getId())).isZero();
    }

    @Test
    void clearingASecondFactorRevokesTheAccountsSessions() throws Exception {
        Enrolled enrolled = enrolAndRotate();

        mockMvc.perform(post("/api/auth/mfa/reset/" + admin().getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + enrolled.fullToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/facility")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + enrolled.fullToken()))
                .andExpect(status().isUnauthorized());

        // And the account is back at enrolment on the next login.
        assertThat(login(ROTATED_PASSWORD).get("scope").asText()).isEqualTo("ENROLL_MFA");
    }

    @Test
    void resettingASecondFactorRequiresAnAuthenticatedAdmin() throws Exception {
        mockMvc.perform(post("/api/auth/mfa/reset/" + java.util.UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anInterimTokenCannotResetASecondFactor() throws Exception {
        enrolAndRotate();
        String pending = login(ROTATED_PASSWORD).get("token").asText();

        mockMvc.perform(post("/api/auth/mfa/reset/" + admin().getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pending))
                .andExpect(status().isUnauthorized());
    }
}

