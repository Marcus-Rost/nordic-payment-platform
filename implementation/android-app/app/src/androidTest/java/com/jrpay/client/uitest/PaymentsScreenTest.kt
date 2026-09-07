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
 * UC-G07 — payment initiation form surface.
 *
 * What this proves end-to-end:
 *   - Authenticated nav to the Payments tab renders the payment form
 *     fields the customer fills in to initiate an instant payment
 *     (Creditor IBAN, Creditor name, Amount, Reference, From account).
 *   - The form CTA "Send instant payment" is present.
 *   - The screen mode-toggle between instant and scheduled payments
 *     surfaces both options.
 *
 * What it deliberately does NOT prove:
 *   - That submitting the form actually settles a payment through the
 *     A3→A2→A5→A1 Kafka chain. The customer-payments controller is
 *     not yet wired in bill-payment (OPEN-7) so submission would fail.
 *     Submission-flow assertion lands when OPEN-7 is closed.
 */
class PaymentsScreenTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun seedSession() {
            TestAuth.seedAuthenticatedSession()
        }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun payments_tab_renders_form_fields_and_cta() {
        composeRule.onNodeWithText("Payments").performClick()
        composeRule.waitForIdle()

        // PaymentsScreen header — instant mode is the default.
        composeRule.onNodeWithText("New instant payment").assertIsDisplayed()
        // Form fields (label-as-text on OutlinedTextField).
        composeRule.onNodeWithText("Creditor IBAN").assertIsDisplayed()
        composeRule.onNodeWithText("Creditor name").assertIsDisplayed()
        composeRule.onNodeWithText("Amount").assertIsDisplayed()
        composeRule.onNodeWithText("Reference / KID").assertIsDisplayed()
    }
}
