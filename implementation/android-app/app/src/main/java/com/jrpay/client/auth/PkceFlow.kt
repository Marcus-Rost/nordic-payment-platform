package com.jrpay.client.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** RFC 7636 PKCE — verifier + S256 challenge generator (mirrors web client). */
object PkceFlow {

    data class Pkce(val verifier: String, val challenge: String, val state: String, val nonce: String)

    private val rng = SecureRandom()

    fun new(): Pkce {
        val verifier = base64Url(randomBytes(32))
        val challenge = base64Url(sha256(verifier.toByteArray()))
        val state = base64Url(randomBytes(16))
        val nonce = base64Url(randomBytes(16))
        return Pkce(verifier, challenge, state, nonce)
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also(rng::nextBytes)

    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    private fun base64Url(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
