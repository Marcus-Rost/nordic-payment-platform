package com.jrpay.client.uitest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jrpay.client.MainActivity
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * UC-G02 + UC-G14 — authenticated dashboard + bottom-nav navigation.
 *
 * What this proves end-to-end:
 *   - Authenticated session routes to the bottom-nav scaffold (not to
 *     LoginScreen).
 *   - Dashboard composable renders its title and either the loading,
 *     error, or ready state — never crashes.
 *   - Bottom-nav has the four canonical tabs (Dashboard, Payments,
 *     Requests, More) and tapping each navigates without crashing.
 *
 * What it deliberately does NOT prove:
 *   - That `/api/v1/customer/accounts` returns a populated list. Today's
 *     account-ledger does not have that controller (404 surfaces as 500
 *     via the generic error handler — OPEN-10). The dashboard's error
 *     state IS exercised by this test, which is correct: graceful
 *     degradation when a backend dependency is missing.
 *
 * Session is seeded in @BeforeClass with a real customer token from
 * identity's /dev/oauth2/dev-token. The token is bound to
 * ola.nordmann@example.com (V003 + F3 bcrypt fix) and lives in the
 * SecureTokenStore until the next clearSession.
 */
class DashboardAndNavTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun seedSessionBeforeActivity() {
            TestAuth.seedAuthenticatedSession()
        }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun authenticated_session_routes_to_dashboard_with_bottom_nav() {
        // Dashboard heading is the canonical authenticated-route marker.
        composeRule.onNodeWithText("Welcome back").assertIsDisplayed()
        // Bottom-nav tabs must all be present.
        composeRule.onNodeWithText("Dashboard").assertIsDisplayed()
        composeRule.onNodeWithText("Payments").assertIsDisplayed()
        composeRule.onNodeWithText("Requests").assertIsDisplayed()
        composeRule.onNodeWithText("More").assertIsDisplayed()
    }

    @Test
    fun bottom_nav_navigates_to_payments_without_crash() {
        composeRule.onNodeWithText("Payments").performClick()
        composeRule.waitForIdle()
        // PaymentsScreen renders the "New instant payment" CTA.
        composeRule.onNodeWithText("New instant payment").assertIsDisplayed()
    }

    @Test
    fun bottom_nav_navigates_to_requests_without_crash() {
        composeRule.onNodeWithText("Requests").performClick()
        composeRule.waitForIdle()
        // R2pInboxScreen renders either the title or the empty-state
        // message; either is acceptable. We assert the screen exists by
        // looking for one of those.
        val title = composeRule.onAllNodesWithText("Payment requests")
        val empty = composeRule.onAllNodesWithText("No active requests")
        assert(title.fetchSemanticsNodes().isNotEmpty() ||
                empty.fetchSemanticsNodes().isNotEmpty()) {
            "expected R2P screen title or empty-state message"
        }
    }

    @Test
    fun bottom_nav_navigates_to_more_without_crash() {
        composeRule.onNodeWithText("More").performClick()
        composeRule.waitForIdle()
        // MoreScreen surfaces logout + secondary destinations. The Logout
        // string is the canonical anchor.
        composeRule.onNodeWithText("Logout").assertIsDisplayed()
    }
}
