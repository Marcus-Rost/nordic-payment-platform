package com.jrpay.client.uitest

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.jrpay.client.MainActivity
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * UC-G01 — login screen.
 *
 * What this proves end-to-end:
 *   - The native LoginScreen composable renders the canonical Sign-in
 *     surface (heading, subtitle, BankID CTA, SCA notice).
 *   - Same content the web client renders on /login (asserted in
 *     implementation/e2e/specs/phaseA-cross-client-smoke.spec.ts) — i.e.
 *     a real cross-client invariant verified through the actual native
 *     Compose UI, not just a phone-viewport shrink of the web SPA.
 *
 * What it deliberately does NOT prove:
 *   - That tapping the BankID button leads to a successful OAuth round-trip.
 *     The button delegates to Custom Tabs (sandboxed system browser) by
 *     design. Driving Chrome from instrumentation tests is a separate
 *     concern; the BankID round-trip is asserted at the backend level
 *     (identity smoke tests + Phase A dev-token spec in the e2e package).
 *
 * Session state is cleared in @BeforeClass so the activity composes with
 * session = null on its very first onCreate (otherwise a token persisted
 * in EncryptedSharedPreferences from a prior test run would silently
 * route us to the dashboard instead of the login screen).
 */
class LoginScreenTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun resetSessionBeforeActivity() {
            TestAuth.clearSession()
        }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun login_screen_renders_canonical_signin_surface() {
        composeRule.onNodeWithText("Sign in", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("Sign in with BankID to open online banking.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Continue with BankID").assertIsDisplayed()
        composeRule.onNodeWithText("Money transfers require BankID confirmation.")
            .assertIsDisplayed()
    }

    @Test
    fun login_screen_does_not_show_bottom_nav_when_unauthenticated() {
        composeRule.onNodeWithText("Continue with BankID").assertIsDisplayed()
        // Negative assertions: bottom-nav tabs must be absent on the
        // unauthenticated screen — verifies we're not leaking authenticated
        // UI to an unauthenticated user.
        listOf("Dashboard", "Payments", "Requests", "More").forEach { tab ->
            composeRule.onAllNodesWithText(tab).assertCountEquals(0)
        }
    }
}
