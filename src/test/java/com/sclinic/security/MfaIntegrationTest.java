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

        String confirmBody = submitCode("/api/auth/mfa/confirm", enrolToken, currentCode(secret))
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
        return new Enrolled(secret, backupCodes, fullToken);
    }

    private record Enrolled(String secret, List<String> backupCodes, String fullToken) {
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
        submitCode("/api/auth/mfa/verify", pending, currentCode(enrolled.secret()))
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

        String body = submitCode("/api/auth/mfa/verify", pending, currentCode(enrolled.secret()))
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

        submitCode("/api/auth/mfa/verify", pending, currentCode(enrolled.secret()))
                .andExpect(status().isOk());

        submitCode("/api/auth/mfa/verify", pending, currentCode(enrolled.secret()))
                .andExpect(status().isUnauthorized());
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
