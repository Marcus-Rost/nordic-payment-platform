package com.jrpay.client.uitest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jrpay.client.MainActivity
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * Phase G — extended authenticated screens.
 *
 * Mirrors the Phase D2 web suite (`phaseD2-customer-authed-screens.spec.ts`)
 * for the native Android client. Each spec navigates to one of the
 * Citizen GUI surfaces via the bottom-nav + the "More" tab and asserts
 * the canonical title is rendered.
 *
 * Submission/CRUD is deferred — those land when the matching backend
 * endpoints are wired (OPEN-7 for customer-payments POST, OPEN-12 for
 * /profile, etc.). The screens render their titles even when a backend
 * call returns 500, so this is a "smoke render" assertion: the route
 * resolves and the screen mounts without crashing.
 *
 * UCs covered:
 *   - UC-G08/G09 mandates
 *   - UC-G16 beneficiaries
 *   - UC-G17/G18 consents
 *   - UC-G19 r2p (additional, beyond DashboardAndNavTest's smoke)
 *   - UC-G24 privacy / GDPR
 *   - UC-G26 profile
 *   - UC-G33 notifications
 */
class AuthedScreensTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun seedSession() {
            TestAuth.seedAuthenticatedSession()
        }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun navigateViaMoreTab(itemLabel: String) {
        composeRule.onNodeWithText("More").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(itemLabel).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun mandates_screen_renders_title() {
        navigateViaMoreTab("AvtaleGiro mandates")
        composeRule.onNodeWithText("AvtaleGiro mandates").assertIsDisplayed()
    }

    @Test
    fun beneficiaries_screen_renders_title() {
        navigateViaMoreTab("Beneficiaries")
        composeRule.onNodeWithText("Beneficiaries").assertIsDisplayed()
    }

    @Test
    fun consents_screen_renders_title() {
        navigateViaMoreTab("Active consents")
        composeRule.onNodeWithText("Active consents").assertIsDisplayed()
    }

    @Test
    fun notifications_screen_renders_title() {
        navigateViaMoreTab("Notifications")
        composeRule.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test
    fun profile_screen_renders_title() {
        navigateViaMoreTab("Profile")
        // Native Compose ProfileScreen renders the title even when
        // /api/v1/customer/profile returns 500 — UI wraps the loading /
        // error state below the heading. (Different from the web SPA's
        // ProfilePage, which early-returns on null profile and is stuck
        // on "Loading…" — the regression-trap pinned in
        // phaseD2-customer-authed-screens.spec.ts; OPEN-12.)
        composeRule.onNodeWithText("Profile").assertIsDisplayed()
    }

    @Test
    fun privacy_screen_renders_title() {
        navigateViaMoreTab("Privacy & GDPR")
        composeRule.onNodeWithText("Privacy & GDPR").assertIsDisplayed()
    }

    @Test
    fun more_tab_exposes_logout() {
        composeRule.onNodeWithText("More").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Logout").assertIsDisplayed()
        // All More-tab navigation items must be visible on this screen.
        listOf(
            "AvtaleGiro mandates",
            "Beneficiaries",
            "Active consents",
            "Notifications",
            "Profile",
            "Privacy & GDPR",
        ).forEach { item ->
            composeRule.onNodeWithText(item).assertIsDisplayed()
        }
    }

    @Test
    fun r2p_screen_via_bottom_nav_renders() {
        composeRule.onNodeWithText("Requests").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Payment requests").assertIsDisplayed()
    }
}
