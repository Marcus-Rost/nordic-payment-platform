package com.jrpay.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.jrpay.client.auth.OAuthCallbackHandler
import com.jrpay.client.ui.JrpayApp
import com.jrpay.client.ui.theme.JrpayTheme
import kotlinx.coroutines.launch

/** Single-activity Compose host. Routing handled by `JrpayApp`. */
class MainActivity : FragmentActivity() {

    private val app: JrpayApplication by lazy { JrpayApplication.get(this) }
    private val callbackHandler by lazy {
        OAuthCallbackHandler(app.oAuthClient, app.sessionStore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JrpayTheme {
                val session by app.sessionStore.state.collectAsState()
                JrpayApp(
                    session = session,
                    sessionStore = app.sessionStore,
                    apiClient = app.apiClient,
                    scaClient = app.scaClient,
                    oAuthClient = app.oAuthClient,
                    offlineCache = app.offlineCache,
                )
            }
        }
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code != null && state != null) {
            lifecycleScope.launch { callbackHandler.complete(code, state) }
        }
    }
}
