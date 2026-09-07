package com.jrpay.client.util

import android.net.Uri
import com.jrpay.client.ui.Routes

/**
 * UC-G29 — push-notification deep-link routing.
 *
 * Translates incoming deep-link URIs into in-app Compose routes. Supported
 * shapes:
 *   - https://app.jrpay.example/r2p/{requestId}     → R2P inbox + scroll-to
 *   - https://app.jrpay.example/payments/{paymentId}→ Payments tab + detail
 *   - https://app.jrpay.example/notifications/{id}  → Notifications + detail
 *
 * Auth state is checked at the navigation site; an unauthenticated deep-link
 * is parked, the customer signs in, and the original deep-link replays via
 * [readReturnTo] in the OAuth callback handler.
 */
object DeepLinkRouter {

    data class Target(val route: String, val arg: String? = null)

    fun parse(uri: Uri?): Target? {
        if (uri == null) return null
        val segments = uri.pathSegments
        if (segments.isEmpty()) return null
        return when (segments[0]) {
            "r2p" -> Target(Routes.R2P, segments.getOrNull(1))
            "payments" -> Target(Routes.PAYMENTS, segments.getOrNull(1))
            "notifications" -> Target(Routes.NOTIFICATIONS, segments.getOrNull(1))
            "mandates" -> Target(Routes.MANDATES, segments.getOrNull(1))
            "accounts" -> Target(Routes.DASHBOARD, segments.getOrNull(1))
            else -> null
        }
    }
}
