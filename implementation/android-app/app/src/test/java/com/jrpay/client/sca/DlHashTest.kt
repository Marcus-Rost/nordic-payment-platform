package com.jrpay.client.sca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Kotlin dynamic-linking hash matches the canonical algorithm
 * used by the Java services and the web clients. Drift here would fail
 * server-side SCA validation with urn:problem:dynamic-linking-mismatch.
 *
 * The expected canonical strings here mirror those in:
 *   - admin-web/src/sca/__tests__/dlHash.test.ts
 *   - client-web/src/sca/__tests__/dlHash.test.ts
 */
class DlHashTest {

    @Test fun `payment-initiation produces the canonical key order required by the server`() {
        val r = DlHash.hash(
            DlHash.PaymentInitiation(
                creditorIban = "NO9300000000000",
                debtorAccountId = "acc-1",
                amount = "100.00",
                currency = "NOK",
                reference = "INVOICE-1",
            )
        )
        assertEquals(
            """{"amount":"100.00","class":"payment-initiation","creditorIban":"NO9300000000000","currency":"NOK","debtorAccountId":"acc-1","reference":"INVOICE-1"}""",
            r.canonical,
        )
        assertTrue(r.hex.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test fun `payment-initiation omits optional fields when absent`() {
        val r = DlHash.hash(
            DlHash.PaymentInitiation(
                creditorIban = "NO9300000000000",
                debtorAccountId = "acc-1",
                amount = "100.00",
                currency = "NOK",
            )
        )
        assertTrue(!r.canonical.contains("reference"))
        assertTrue(!r.canonical.contains("executionDate"))
    }

    @Test fun `mandate-action CREATE includes cap fields`() {
        val r = DlHash.hash(
            DlHash.MandateAction(
                action = "CREATE",
                creditorId = "creditor-1",
                debtorAccountId = "acc-1",
                capAmount = "500.00",
                capCurrency = "NOK",
            )
        )
        assertEquals(
            """{"action":"CREATE","capAmount":"500.00","capCurrency":"NOK","class":"mandate-action","creditorId":"creditor-1","debtorAccountId":"acc-1"}""",
            r.canonical,
        )
    }

    @Test fun `mandate-action TERMINATE omits cap fields`() {
        val r = DlHash.hash(
            DlHash.MandateAction(
                action = "TERMINATE",
                creditorId = "creditor-1",
                debtorAccountId = "acc-1",
            )
        )
        assertTrue(!r.canonical.contains("capAmount"))
    }

    @Test fun `gdpr-action canonicalises four required keys`() {
        val r = DlHash.hash(
            DlHash.GdprAction(
                action = "EXPORT",
                customerId = "cust-1",
                notesHash = "a".repeat(64),
            )
        )
        assertEquals(
            """{"action":"EXPORT","class":"gdpr-action","customerId":"cust-1","notesHash":"${"a".repeat(64)}"}""",
            r.canonical,
        )
    }

    @Test fun `consent-grant preserves accountIds insertion order`() {
        val r = DlHash.hash(
            DlHash.ConsentGrant(
                consentType = "AISP",
                tppId = "tpp-1",
                expiresAt = "2026-12-31T00:00:00Z",
                accountIds = listOf("acc-z", "acc-a"),
            )
        )
        assertTrue(r.canonical.contains(""""accountIds":["acc-z","acc-a"]"""))
    }

    @Test fun `sha256HexNfc normalises composed and decomposed forms identically`() {
        val composed = "caf" + 'é'         // U+00E9
        val decomposed = "cafe" + '́'       // U+0065 + U+0301
        assertNotEquals(composed, decomposed)
        assertEquals(DlHash.sha256HexNfc(composed), DlHash.sha256HexNfc(decomposed))
    }

    @Test fun `dlHash produces lowercase 64-char hex`() {
        val r = DlHash.hash(mapOf("x" to "y"))
        assertTrue(r.hex.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test fun `escapes quote backslash and newline`() {
        val r = DlHash.hash(mapOf("k" to "a\"b\\c\n"))
        assertEquals("""{"k":"a\"b\\c\n"}""", r.canonical)
    }

    @Test fun `arrays preserve insertion order while objects sort by key`() {
        val r = DlHash.hash(
            mapOf(
                "arr" to listOf("z", "a", "b"),
                "obj" to mapOf("y" to "b", "x" to "a"),
            )
        )
        assertEquals(
            """{"arr":["z","a","b"],"obj":{"x":"a","y":"b"}}""",
            r.canonical,
        )
    }
}
