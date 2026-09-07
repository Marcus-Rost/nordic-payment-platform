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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end tests derived from UC-01 (Verify Beneficiary Name Before Payment).
 * Each test maps to a specific scenario in the use case document.
 *
 * @see space/21_Verification_of_Payee_Service/use_cases/UC01_verify_before_payment.md
 */
@SpringBootTest
@AutoConfigureMockMvc
class UseCaseEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NameCache nameCache;

    @BeforeEach
    void setUp() {
        // Seed cache with test accounts (simulates A1 data)
        nameCache.put("NO9386011117947",
                NameCacheEntry.of("NO9386011117947", "Ola Nordmann", Instant.now()));
        nameCache.put("NO9386011117948",
                NameCacheEntry.of("NO9386011117948", "Ola Nordmann AS", Instant.now()));
        nameCache.put("NO9386011117949",
                NameCacheEntry.of("NO9386011117949", "Kari Hansen", Instant.now()));
        nameCache.put("NO9386011117950",
                NameCacheEntry.of("NO9386011117950", "Bjorn Nilsen", Instant.now()));
        nameCache.put("NO9386011117951",
                NameCacheEntry.of("NO9386011117951", "Gronn Energi Aksjeselskap", Instant.now()));
    }

    // ====================================================================
    // Scenario 1A: Requester — Exact Match (Happy Path)
    // ====================================================================

    @Test
    @WithMockUser
    @DisplayName("UC-01 Scenario 1A: Exact match → MATCH, no actual_name needed")
    void scenario1A_exactMatch() throws Exception {
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "NO9386011117947",
                                    "statedName": "Ola Nordmann",
                                    "requestId": "req-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("MATCH"))
                .andExpect(jsonPath("$.similarityScore").value(closeTo(1.0, 0.01)))
                .andExpect(jsonPath("$.requestId").value("req-001"));
    }

    // ====================================================================
    // Scenario 1B: Requester — Close Match (Typo or Missing Suffix)
    // ====================================================================

    @Test
    @WithMockUser
    @DisplayName("UC-01 Scenario 1B: Close match — typo in name → CLOSE_MATCH with actual_name")
    void scenario1B_closeMatchTypo() throws Exception {
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "NO9386011117948",
                                    "statedName": "Ola Nordman",
                                    "requestId": "req-002"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("CLOSE_MATCH"))
                .andExpect(jsonPath("$.actualName").value("Ola Nordmann AS"))
                .andExpect(jsonPath("$.similarityScore").value(greaterThan(0.0)));
    }

    // ====================================================================
    // Scenario 1C: Requester — No Match (Potential Fraud)
    // ====================================================================

    @Test
    @WithMockUser
    @DisplayName("UC-01 Scenario 1C: No match → NO_MATCH, actual_name NEVER revealed")
    void scenario1C_noMatch() throws Exception {
        // "Ola Nordmann" vs account holder "Kari Hansen" → NO_MATCH
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "NO9386011117949",
                                    "statedName": "Ola Nordmann",
                                    "requestId": "req-003"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("NO_MATCH"))
                .andExpect(jsonPath("$.actualName").doesNotExist());
    }

    // ====================================================================
    // Scenario 1D: Requester — Nordic Diacritics
    // ====================================================================

    @Test
    @WithMockUser
    @DisplayName("UC-01 Scenario 1D: Bjørn = Bjorn (diacritics normalized) → MATCH")
    void scenario1D_nordicDiacritics() throws Exception {
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "NO9386011117950",
                                    "statedName": "Bjørn Nilsen",
                                    "requestId": "req-004"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("MATCH"));
    }

    @Test
    @WithMockUser
    @DisplayName("UC-01 Scenario 1D: Grønn Energi AS = Gronn Energi Aksjeselskap → MATCH")
    void scenario1D_diacriticsPlusAbbreviation() throws Exception {
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "NO9386011117951",
                                    "statedName": "Grønn Energi AS",
                                    "requestId": "req-005"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("MATCH"));
    }

    // ====================================================================
    // Scenario 1E: Responder — External PSP Queries Us
    // ====================================================================

    @Test
    @WithMockUser
    @DisplayName("UC-01 Scenario 1E: External responder — exact match → MATCH only")
    void scenario1E_responderExactMatch() throws Exception {
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
    @DisplayName("UC-01 Scenario 1E: External responder — no match → NO_MATCH, name never revealed")
    void scenario1E_responderNoMatch() throws Exception {
        mockMvc.perform(post("/api/v1/vop/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "iban": "NO9386011117949",
                                    "name": "Ola Nordmann"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("NO_MATCH"))
                .andExpect(jsonPath("$.nameSuggestion").doesNotExist());
    }

    // ====================================================================
    // Scenario: IBAN Not Found
    // ====================================================================

    @Test
    @WithMockUser
    @DisplayName("UC-01: Nonexistent IBAN → NOT_FOUND")
    void nonexistentIban() throws Exception {
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "NO0000000000000",
                                    "statedName": "Anyone",
                                    "requestId": "req-006"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("NOT_FOUND"));
    }

    // ====================================================================
    // Security: NO_MATCH never reveals actual name (structural verification)
    // ====================================================================

    @Test
    @WithMockUser
    @DisplayName("Security: actualName is structurally absent on NO_MATCH (not just null)")
    void securityNoMatchNeverRevealsName() throws Exception {
        mockMvc.perform(post("/api/v1/vop/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "beneficiaryIban": "NO9386011117949",
                                    "statedName": "Completely Wrong Name",
                                    "requestId": "req-007"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("NO_MATCH"))
                // Verify the field is absent, not just null
                .andExpect(jsonPath("$.actualName").doesNotExist());
    }

    @Test
    @WithMockUser
    @DisplayName("Security: nameSuggestion absent on NO_MATCH in external API")
    void securityExternalNoMatchNeverRevealsName() throws Exception {
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
}
