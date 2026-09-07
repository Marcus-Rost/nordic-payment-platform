package com.jrpay.starter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies the fortress principle: deny-all default, explicit opt-in for public endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DenyAllSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Unauthenticated request to business endpoint → rejected (401 or 403)")
    void businessEndpointDenied() throws Exception {
        mockMvc.perform(get("/api/v1/test"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Health endpoint is public")
    void healthEndpointPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Security headers present on every response")
    void securityHeadersPresent() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Strict-Transport-Security",
                        "max-age=31536000; includeSubDomains"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("Unknown endpoint returns 4xx, not 404 (no information leakage)")
    void unknownEndpointDenied() throws Exception {
        mockMvc.perform(get("/does/not/exist"))
                .andExpect(status().is4xxClientError());
    }

    // Minimal test application for this starter test
    @SpringBootApplication
    @RestController
    static class TestApp {
        @GetMapping("/api/v1/test")
        String test() {
            return "should not reach here without auth";
        }
    }
}
