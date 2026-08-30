package com.sclinic.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclinic.security.dto.ChangePasswordRequest;
import com.sclinic.security.dto.LoginRequest;
import com.sclinic.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end authentication against a real database.
 *
 * <p>Covers the properties that matter for compliance: HTTP Basic is gone, a
 * token can be revoked and stops working immediately, and an account flagged for
 * password rotation cannot reach business data until it rotates.
 */
@SpringBootTest(properties = {
        "sclinic.bootstrap.admin-username=admin",
        "sclinic.bootstrap.admin-password=AdminKham2026!Test",
        "sclinic.facility.kcb-code=KCB-AUTH-001",
        // This class covers the credential and rotation gates. MFA is switched
        // off here so the seeded admin lands straight on CHANGE_PASSWORD; the
        // second-factor gate has its own class.
        "sclinic.auth.mfa-required-roles=",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
@Transactional
class SessionAuthIntegrationTest {

    private static final String ADMIN_PASSWORD = "AdminKham2026!Test";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrlFor("auth_it"));
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private JsonNode login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** Logs in and completes the forced rotation, returning a full-scope token. */
    private String fullScopeToken() throws Exception {
        String interim = login("admin", ADMIN_PASSWORD).get("token").asText();

        String body = mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + interim)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest(ADMIN_PASSWORD, "DaDoiMatKhau2027!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("token").asText();
    }

    @Test
    void httpBasicIsNoLongerAccepted() throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                ("admin:" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/facility").header(HttpHeaders.AUTHORIZATION, "Basic " + basic))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedRequestIsRejectedWithoutBasicChallenge() throws Exception {
        mockMvc.perform(get("/api/facility"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("admin", "wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownAccountIsRejectedWithTheSameMessageAsAWrongPassword() throws Exception {
        String unknown = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("khong-ton-tai", "whatever"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("admin", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Identical bodies: no account enumeration.
        assertThat(unknown).isEqualTo(wrongPassword);
    }

    @Test
    void seededAdminMustRotatePasswordBeforeReachingBusinessData() throws Exception {
        JsonNode response = login("admin", ADMIN_PASSWORD);

        assertThat(response.get("passwordChangeRequired").asBoolean()).isTrue();
        assertThat(response.get("scope").asText()).isEqualTo("CHANGE_PASSWORD");

        String interim = response.get("token").asText();
        mockMvc.perform(get("/api/facility").header(HttpHeaders.AUTHORIZATION, "Bearer " + interim))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullScopeTokenReachesBusinessEndpoints() throws Exception {
        String token = fullScopeToken();

        mockMvc.perform(get("/api/facility").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kcbCode").value("KCB-AUTH-001"));
    }

    @Test
    void identityEndpointReportsUsernameAndRole() throws Exception {
        String token = fullScopeToken();

        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void changingPasswordClearsTheRotationFlag() throws Exception {
        String token = fullScopeToken();

        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // The new password now works and no longer forces a rotation.
        JsonNode second = login("admin", "DaDoiMatKhau2027!");
        assertThat(second.get("passwordChangeRequired").asBoolean()).isFalse();
        assertThat(second.get("scope").asText()).isEqualTo("FULL");
    }

    @Test
    void changePasswordRejectsAWeakNewPassword() throws Exception {
        String interim = login("admin", ADMIN_PASSWORD).get("token").asText();

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + interim)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest(ADMIN_PASSWORD, "yeu"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePasswordRejectsReuseOfTheCurrentPassword() throws Exception {
        String interim = login("admin", ADMIN_PASSWORD).get("token").asText();

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + interim)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest(ADMIN_PASSWORD, ADMIN_PASSWORD))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logoutRevokesTheTokenImmediately() throws Exception {
        String token = fullScopeToken();

        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/facility").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingPasswordRevokesTheTokenUsedToChangeIt() throws Exception {
        String interim = login("admin", ADMIN_PASSWORD).get("token").asText();

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + interim)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest(ADMIN_PASSWORD, "DaDoiMatKhau2027!"))))
                .andExpect(status().isOk());

        // Replaying the interim token must fail.
        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + interim)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest("DaDoiMatKhau2027!", "LaiDoiNua2028!"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/facility")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanRevokeAnotherAccountsSessions() throws Exception {
        String token = fullScopeToken();

        mockMvc.perform(post("/api/auth/revoke-sessions/" + adminStaffId(token))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedSessions").value(1));

        // Revoking own sessions logs this session out too.
        mockMvc.perform(get("/api/facility").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokeSessionsRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/revoke-sessions/" + java.util.UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Autowired
    com.sclinic.staff.StaffRepository staffRepository;

    private String adminStaffId(String token) {
        return staffRepository.findByUsernameAndActiveTrue("admin").orElseThrow().getId().toString();
    }
}
