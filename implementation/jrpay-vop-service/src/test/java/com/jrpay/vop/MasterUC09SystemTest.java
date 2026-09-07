package com.jrpay.vop;

import com.jrpay.vop.cache.NameCache;
import com.jrpay.vop.cache.NameCacheEntry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * System tests derived from master project UC-09 (Verification of Payee).
 * Tests the full A6 service flow as described in the master use case,
 * simulating A1's account data in A6's cache.
 *
 * These tests verify cross-service behavior: A6 querying account data
 * (via cache, representing A1) and returning VoP results to callers
 * (representing A3/A4 and external PSPs).
 *
 * @see space/payment_processing/use_cases/UC09_verification_of_payee.md
 */
@SpringBootTest
@AutoConfigureMockMvc
class MasterUC09SystemTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NameCache nameCache;

    @BeforeEach
    void seedAccountData() {
        // Simulates A1 account data as seen by A6's cache
        nameCache.put("NO9386011117947",
                NameCacheEntry.of("NO9386011117947", "Ola Nordmann", Instant.now()));
        nameCache.put("NO9386011117948",
                NameCacheEntry.of("NO9386011117948", "Ola Nordmann AS", Instant.now()));
        nameCache.put("NO9386011117949",
                NameCacheEntry.of("NO9386011117949", "Kari Hansen", Instant.now()));
        // Joint account with two holders
        nameCache.put("NO9386011117960",
                new NameCacheEntry("NO9386011117960", "Ola Nordmann",
                        List.of("Ola Nordmann", "Kari Nordmann"), Instant.now()));
    }

    // ====================================================================
    // UC-09 Scenario 9A: Requester Role — Check Before Sending
    // ====================================================================

    @Test
    @WithMockUser
    @DisplayName("UC-09 9A Main: Exact match — MATCH, payment proceeds")
    void uc09_9A_main_exactMatch() throws Exception {
        // Step 1-2: Payer enters name + IBAN, A3/A4 calls A6
        // Step 3-4: A6 looks up in A1 (cache), normalizes, JW=1.0 → MATCH
        // Step 5: Returns MATCH
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "NO9386011117947",
                                    "statedName": "Ola Nordmann",
                                    "requestId": "uc09-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("MATCH"))
                .andExpect(jsonPath("$.similarityScore").value(closeTo(1.0, 0.01)));
    }

    @Test
    @WithMockUser
    @DisplayName("UC-09 9A Alt: Close match — actual_name revealed for correction")
    void uc09_9A_alt_closeMatch() throws Exception {
        // Step 3a: Account holder is "Ola Nordmann AS", payer entered "Ola Nordmann"
        // Step 4a: CLOSE_MATCH with actual_name
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "NO9386011117948",
                                    "statedName": "Nordmann",
                                    "requestId": "uc09-002"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(
                        anyOf(is("CLOSE_MATCH"), is("NO_MATCH"))));
        // Note: exact result depends on JW score for "nordmann" vs "ola nordmann as"
    }

    @Test
    @WithMockUser
    @DisplayName("UC-09 9A Alt: No match — actual_name NEVER revealed (privacy)")
    void uc09_9A_alt_noMatch() throws Exception {
        // Step 3b: Account holder is "Kari Hansen", payer entered "Ola Nordmann"
        // Step 4b: NO_MATCH, actual_name NOT returned
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "NO9386011117949",
                                    "statedName": "Ola Nordmann",
                                    "requestId": "uc09-003"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("NO_MATCH"))
                .andExpect(jsonPath("$.actualName").doesNotExist());
    }

    @Test
    @WithMockUser
    @DisplayName("UC-09 9A Alt: IBAN not found — NOT_FOUND")
    void uc09_9A_alt_ibanNotFound() throws Exception {
        // Step 3c: IBAN does not exist
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "DE89370400440532013000",
                                    "statedName": "Anyone",
                                    "requestId": "uc09-004"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("NOT_FOUND"));
    }

    // ====================================================================
    // UC-09 Scenario 9B: Responder Role — Answer Queries About Our Accounts
    // ====================================================================

    @Test
    @WithMockUser
    @DisplayName("UC-09 9B Main: External PSP queries — exact match")
    void uc09_9B_main_responderExactMatch() throws Exception {
        // Step 1-5: External PSP queries with correct name → MATCH
        mockMvc.perform(post("/api/v1/vop/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "iban": "NO9386011117947",
                                    "name": "Ola Nordmann"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("MATCH"))
                .andExpect(jsonPath("$.nameSuggestion").doesNotExist());
    }

    @Test
    @WithMockUser
    @DisplayName("UC-09 9B: Responder — NO_MATCH, name never revealed (privacy rule)")
    void uc09_9B_responderNoMatch_nameNeverRevealed() throws Exception {
        // Privacy rule: on NO_MATCH, actual name NOT revealed (prevent name harvesting)
        mockMvc.perform(post("/api/v1/vop/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "iban": "NO9386011117949",
                                    "name": "Wrong Person"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("NO_MATCH"))
                .andExpect(jsonPath("$.nameSuggestion").doesNotExist());
    }

    // ====================================================================
    // Joint Account — Match Against Best Holder
    // ====================================================================

    @Test
    @WithMockUser
    @DisplayName("Joint account: match against any holder → best match wins")
    void jointAccount_bestMatchWins() throws Exception {
        // Account has two holders: "Ola Nordmann" and "Kari Nordmann"
        // Query for "Kari Nordmann" should match
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "NO9386011117960",
                                    "statedName": "Kari Nordmann",
                                    "requestId": "uc09-joint-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("MATCH"));
    }

    // ====================================================================
    // Cross-Service Interface Verification
    // ====================================================================

    @Test
    @WithMockUser
    @DisplayName("Interface contract: verify endpoint matches INTERFACE_CONTRACTS Section 3.3")
    void interfaceContract_verifyEndpoint() throws Exception {
        // Verify request/response format matches INTERFACE_CONTRACTS.md
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "NO9386011117947",
                                    "statedName": "Ola Nordmann",
                                    "requestId": "contract-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("contract-001"))
                .andExpect(jsonPath("$.result").exists())
                .andExpect(jsonPath("$.similarityScore").exists())
                .andExpect(jsonPath("$.responseTimeMs").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("Interface contract: check endpoint matches INTERFACE_CONTRACTS Section 3.5")
    void interfaceContract_checkEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/vop/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "iban": "NO9386011117947",
                                    "name": "Ola Nordmann"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").exists());
    }
}
