package com.jrpay.client.auth

import android.content.Context
import android.util.Base64
import com.jrpay.client.api.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Session = decoded JWT payload + the bytes the API client needs. The
 * EncryptedSharedPreferences-backed [SecureTokenStore] is the durable layer;
 * this class merely caches the in-memory representation for the UI.
 */
class SessionStore(context: Context) {

    private val secure = SecureTokenStore(context)
    private val _state = MutableStateFlow<Session?>(loadInitial())
    val state: StateFlow<Session?> = _state

    val bearerProvider: ApiClient.BearerProvider = ApiClient.BearerProvider {
        val s = _state.value ?: return@BearerProvider null
        if (s.isExpired()) null else s.accessToken
    }

    fun save(accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        val now = System.currentTimeMillis() / 1000
        val expiresAt = now + expiresInSeconds
        secure.accessToken = accessToken
        if (refreshToken != null) secure.refreshToken = refreshToken
        secure.expiresAtSeconds = expiresAt
        _state.value = Session(accessToken, refreshToken, expiresAt, decodeJwtClaims(accessToken))
    }

    fun clear() {
        secure.clear()
        _state.value = null
    }

    private fun loadInitial(): Session? {
        val token = secure.accessToken ?: return null
        val claims = decodeJwtClaims(token)
        val now = System.currentTimeMillis() / 1000
        val expiresAt = secure.expiresAtSeconds.takeIf { it > 0 } ?: claims.exp ?: 0
        if (expiresAt <= now) return null
        return Session(token, secure.refreshToken, expiresAt, claims)
    }

    @Serializable
    data class Session(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtSeconds: Long,
        val claims: JwtClaims,
    ) {
        fun isExpired(skewSeconds: Long = 30): Boolean {
            val now = System.currentTimeMillis() / 1000
            return now >= expiresAtSeconds - skewSeconds
        }
        fun expiresInSeconds(): Long = (expiresAtSeconds - System.currentTimeMillis() / 1000)
            .coerceAtLeast(0)
    }

    @Serializable
    data class JwtClaims(
        val sub: String? = null,
        val exp: Long? = null,
        val aud: String? = null,
        val scope: String? = null,
        val acr: String? = null,
        val jrpay_uid: String? = null,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decodeJwtClaims(token: String): JwtClaims {
            val parts = token.split('.')
            if (parts.size < 2) return JwtClaims()
            return runCatching {
                val payloadBytes = Base64.decode(
                    parts[1],
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )
                val raw = String(payloadBytes, Charsets.UTF_8)
                val obj = json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching JwtClaims()
                fun get(key: String): String? =
                    (obj[key] as? JsonElement)?.jsonPrimitive?.contentOrNull
                fun getLong(key: String): Long? =
                    (obj[key] as? JsonElement)?.jsonPrimitive?.longOrNull
                JwtClaims(
                    sub = get("sub"),
                    exp = getLong("exp"),
                    aud = get("aud"),
                    scope = get("scope"),
                    acr = get("acr"),
                    jrpay_uid = get("jrpay_uid"),
                )
            }.getOrDefault(JwtClaims())
        }
    }
}
