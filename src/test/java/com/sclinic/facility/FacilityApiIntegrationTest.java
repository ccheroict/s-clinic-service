package com.sclinic.facility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclinic.facility.dto.FacilityRequest;
import com.sclinic.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the facility endpoints end to end against a real database,
 * including the role rules that protect them.
 */
@SpringBootTest(properties = {
        "sclinic.facility.name=Phong kham API",
        "sclinic.facility.kcb-code=KCB-API-001",
        "logging.level.org.hibernate.SQL=warn"
})
@AutoConfigureMockMvc
// Each test rolls back so the update tests cannot leak state into the read test.
// Audit writes use REQUIRES_NEW and are intentionally unaffected by this rollback.
@Transactional
class FacilityApiIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrlFor("facility_api_it"));
        registry.add("spring.datasource.username", EmbeddedPostgresSupport::username);
        registry.add("spring.datasource.password", EmbeddedPostgresSupport::password);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private static FacilityRequest request(String name, String kcbCode) {
        return new FacilityRequest(name, kcbCode, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    void getRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/facility"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "letan", roles = "RECEPTIONIST")
    void anyAuthenticatedUserCanReadFacility() throws Exception {
        mockMvc.perform(get("/api/facility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Phong kham API"))
                .andExpect(jsonPath("$.kcbCode").value("KCB-API-001"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void adminCanUpdateFacility() throws Exception {
        FacilityRequest body = new FacilityRequest("Phong kham da lieu Sai Gon", "KCB-API-001",
                "LT-0001", "0301234567", "12 Nguyen Hue, Quan 1", "02838220000",
                "lienhe@sclinic.vn", "GP-2024-118", null, "BS Tran Thi B",
                "1", "C25TAA", "VNPT-UNIT-9");

        mockMvc.perform(put("/api/facility")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Phong kham da lieu Sai Gon"))
                .andExpect(jsonPath("$.interopCode").value("LT-0001"))
                .andExpect(jsonPath("$.einvoiceSerial").value("C25TAA"))
                .andExpect(jsonPath("$.technicalDirector").value("BS Tran Thi B"));
    }

    @Test
    @WithMockUser(username = "bacsi", roles = "DOCTOR")
    void doctorCannotUpdateFacility() throws Exception {
        mockMvc.perform(put("/api/facility")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("Doi ten", "KCB-API-001"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "letan", roles = "RECEPTIONIST")
    void receptionistCannotUpdateFacility() throws Exception {
        mockMvc.perform(put("/api/facility")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("Doi ten", "KCB-API-001"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void blankFacilityCodeIsRejectedWith400() throws Exception {
        mockMvc.perform(put("/api/facility")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("Phong kham", "   "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void blankNameIsRejectedWith400() throws Exception {
        mockMvc.perform(put("/api/facility")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("  ", "KCB-API-001"))))
                .andExpect(status().isBadRequest());
    }
}
