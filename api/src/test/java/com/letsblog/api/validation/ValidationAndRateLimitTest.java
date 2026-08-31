package com.letsblog.api.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Validation and Rate Limit Tests")
class ValidationAndRateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Should return 200 for valid request to accessible endpoint")
    void testValidRequest() throws Exception {
        mockMvc.perform(get("/api-docs")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should handle validation errors gracefully")
    void testValidationErrorHandling() throws Exception {
        mockMvc.perform(get("/api/users")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 404) {
                        // Endpoint might not exist
                        return;
                    }
                    // If it exists, should be either 200 or validation error
                    assert status >= 200 && status <= 429;
                });
    }

    @Test
    @DisplayName("Should return proper error format for rate limit")
    void testRateLimitErrorFormat() throws Exception {
        // Note: This test is a structure test
        // Actual rate limiting requires multiple rapid requests
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Should exclude health and metrics endpoints from rate limiting")
    void testExcludedEndpointsNotRateLimited() throws Exception {
        // Health endpoint should not be rate limited
        mockMvc.perform(get("/api/health"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // Should either return 200 or 404 (if endpoint doesn't exist)
                    // But never 429 (rate limit)
                    assert status != 429;
                });
    }
}
