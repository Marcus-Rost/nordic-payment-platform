package com.jrpay.client

import android.app.Application
import com.jrpay.client.api.ApiClient
import com.jrpay.client.auth.OAuthClient
import com.jrpay.client.auth.SessionStore
import com.jrpay.client.data.OfflineCache
import com.jrpay.client.sca.ScaClient

/**
 * Manual DI graph. Hilt was deliberately left out to keep the module thin;
 * if the surface grows past ~10 services, swap to Hilt + ksp.
 */
class JrpayApplication : Application() {

    lateinit var sessionStore: SessionStore
        private set
    lateinit var apiClient: ApiClient
        private set
    lateinit var oAuthClient: OAuthClient
        private set
    lateinit var scaClient: ScaClient
        private set
    lateinit var offlineCache: OfflineCache
        private set

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
        oAuthClient = OAuthClient(identityBaseUrl = BuildConfig.IDENTITY_BASE_URL)
        apiClient = ApiClient(
            baseUrl = BuildConfig.API_BASE_URL,
            bearerProvider = sessionStore.bearerProvider,
        )
        scaClient = ScaClient(apiClient)
        offlineCache = OfflineCache.create(this)
    }

    companion object {
        fun get(context: android.content.Context): JrpayApplication =
            context.applicationContext as JrpayApplication
    }
}
