package com.jrpay.client.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OAuth2 authorization-code + PKCE flow against the JRPay identity service.
 *
 * The customer authenticates via BankID upstream of identity; on the device
 * we open Custom Tabs against /oauth2/authorize and listen for the deep-link
 * callback (registered in AndroidManifest). The callback's `code` + `state`
 * are exchanged for a token at /oauth2/token.
 */
class OAuthClient(
    private val identityBaseUrl: String,
    private val clientId: String = DEFAULT_CLIENT_ID,
    private val redirectUri: String = DEFAULT_REDIRECT_URI,
    private val scope: String = "openid customer:read customer:write",
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    @Serializable
    data class TokenResponse(
        val access_token: String,
        val refresh_token: String? = null,
        val token_type: String,
        val expires_in: Long,
        val scope: String? = null,
        val id_token: String? = null,
    )

    fun launchAuthorize(context: Context, pkce: PkceFlow.Pkce) {
        val uri = Uri.parse("$identityBaseUrl/oauth2/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", scope)
            .appendQueryParameter("state", pkce.state)
            .appendQueryParameter("nonce", pkce.nonce)
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }

    suspend fun exchangeCode(code: String, verifier: String): TokenResponse =
        tokenCall(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "code_verifier" to verifier,
            )
        )

    suspend fun refresh(refreshToken: String): TokenResponse =
        tokenCall(
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
            )
        )

    suspend fun revoke(refreshToken: String) {
        runCatching {
            tokenCall(
                mapOf("token" to refreshToken, "client_id" to clientId),
                path = "/oauth2/revoke",
            )
        }
    }

    private suspend fun tokenCall(
        params: Map<String, String>,
        path: String = "/oauth2/token",
    ): TokenResponse = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        val req = Request.Builder()
            .url("$identityBaseUrl$path")
            .post(body)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "Token call failed (${resp.code}): $raw" }
            json.decodeFromString(raw)
        }
    }

    private companion object {
        const val DEFAULT_CLIENT_ID = "jrpay-client-android"
        const val DEFAULT_REDIRECT_URI = "jrpayclient://oauth-callback"
    }
}
