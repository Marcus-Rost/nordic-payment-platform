package com.jrpay.client.uitest

import androidx.test.platform.app.InstrumentationRegistry
import com.jrpay.client.JrpayApplication
import com.jrpay.client.auth.SessionStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Test-only helpers that bypass the BankID/Custom Tabs OAuth round-trip by
 * acquiring a Bearer token directly from identity's `/dev/oauth2/dev-token`
 * endpoint and seeding it into the running app's [SessionStore].
 *
 * The dev-token endpoint is gated by the `docker`/`demo`/`smoke` Spring
 * profiles in identity, so this works only against a non-prod stack — exactly
 * the surface the connected-Android tests run against.
 *
 * Why this exists: the BankID flow is intentionally delegated to Custom Tabs
 * (a sandboxed system browser session); driving Chrome from instrumentation
 * tests would require dismissing Chrome's first-run wizard and scripting an
 * external OAuth UI. Skipping that here lets us assert the *post-auth*
 * native UI directly, which is what these tests are about.
 */
object TestAuth {

    private const val IDENTITY_BASE = "http://10.0.2.2:8097"
    private const val DEMO_USER = "ola.nordmann@example.com"
    private const val DEMO_PASS = "demo-password-1!"

    private val http = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Issue a customer-audience Bearer token from the dev-token endpoint. */
    fun issueCustomerToken(): TokenResult {
        val payload = JSONObject().apply {
            put("username", DEMO_USER)
            put("password", DEMO_PASS)
            put("audience", "customer")
        }
        val req = Request.Builder()
            .url("$IDENTITY_BASE/dev/oauth2/dev-token")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            require(resp.isSuccessful) {
                "dev-token failed (${resp.code}): ${body.take(200)}"
            }
            val json = JSONObject(body)
            return TokenResult(
                accessToken = json.getString("access_token"),
                expiresInSeconds = json.optLong("expires_in", 900L),
            )
        }
    }

    /**
     * Acquire a token and write it into the app's SessionStore. Subsequent
     * Compose composition will see [SessionStore.state] as non-null and route
     * to the bottom-nav scaffold (Dashboard) instead of LoginScreen.
     */
    fun seedAuthenticatedSession() {
        val token = issueCustomerToken()
        val app = JrpayApplication.get(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        app.sessionStore.save(
            accessToken = token.accessToken,
            refreshToken = null,
            expiresInSeconds = token.expiresInSeconds,
        )
    }

    /** Reset the session so the next composition re-renders the LoginScreen. */
    fun clearSession() {
        val app = JrpayApplication.get(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        app.sessionStore.clear()
    }

    data class TokenResult(val accessToken: String, val expiresInSeconds: Long)
}
