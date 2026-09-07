package com.jrpay.client.auth

/**
 * Holds the in-flight PKCE verifier between [launchAuthorize] and the
 * [Intent.ACTION_VIEW] deep-link callback. The verifier is also persisted
 * to [SecureTokenStore] so configuration changes (rotation, process death)
 * don't drop it.
 */
class OAuthCallbackHandler(
    private val oAuthClient: OAuthClient,
    private val sessionStore: SessionStore,
) {

    suspend fun complete(code: String, state: String) {
        val pending = pendingByState.remove(state) ?: return
        if (pending.state != state) return
        val tokens = oAuthClient.exchangeCode(code, pending.verifier)
        sessionStore.save(
            accessToken = tokens.access_token,
            refreshToken = tokens.refresh_token,
            expiresInSeconds = tokens.expires_in,
        )
    }

    fun stash(pkce: PkceFlow.Pkce) {
        pendingByState[pkce.state] = pkce
    }

    private companion object {
        // Parallel logins are not a customer use case, so an in-memory map
        // is sufficient. Process death wipes it; the customer simply
        // restarts the BankID flow.
        val pendingByState: MutableMap<String, PkceFlow.Pkce> = mutableMapOf()
    }
}
