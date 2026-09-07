package com.jrpay.client.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jrpay.client.R
import com.jrpay.client.api.ApiClient
import com.jrpay.client.auth.OAuthClient
import com.jrpay.client.auth.SessionStore
import com.jrpay.client.data.OfflineCache
import com.jrpay.client.sca.ScaClient
import com.jrpay.client.ui.screens.LoginScreen
import com.jrpay.client.ui.screens.beneficiaries.BeneficiariesScreen
import com.jrpay.client.ui.screens.consents.ConsentsScreen
import com.jrpay.client.ui.screens.dashboard.DashboardScreen
import com.jrpay.client.ui.screens.mandates.MandatesScreen
import com.jrpay.client.ui.screens.notifications.NotificationsScreen
import com.jrpay.client.ui.screens.payments.PaymentsScreen
import com.jrpay.client.ui.screens.privacy.PrivacyScreen
import com.jrpay.client.ui.screens.profile.ProfileScreen
import com.jrpay.client.ui.screens.r2p.R2pInboxScreen
import com.jrpay.client.ui.screens.MoreScreen

@Composable
fun JrpayApp(
    session: SessionStore.Session?,
    sessionStore: SessionStore,
    apiClient: ApiClient,
    scaClient: ScaClient,
    oAuthClient: OAuthClient,
    offlineCache: OfflineCache,
) {
    val nav = rememberNavController()
    if (session == null) {
        LoginScreen(oAuthClient = oAuthClient)
        return
    }
    Scaffold(
        bottomBar = { JrpayBottomBar(nav) }
    ) { padding ->
        AppNavGraph(
            nav = nav,
            padding = padding,
            apiClient = apiClient,
            scaClient = scaClient,
            sessionStore = sessionStore,
            offlineCache = offlineCache,
        )
    }
}

@Composable
private fun JrpayBottomBar(nav: NavHostController) {
    val current = nav.currentBackStackEntryAsState().value
    val route = current?.destination?.route
    NavigationBar {
        Tab(
            label = R.string.nav_dashboard,
            icon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
            selected = route == Routes.DASHBOARD,
            onClick = { nav.navigateTab(Routes.DASHBOARD) }
        )
        Tab(
            label = R.string.nav_payments,
            icon = { Icon(Icons.Default.Payments, contentDescription = null) },
            selected = route == Routes.PAYMENTS,
            onClick = { nav.navigateTab(Routes.PAYMENTS) }
        )
        Tab(
            label = R.string.nav_r2p,
            icon = { Icon(Icons.Default.Inbox, contentDescription = null) },
            selected = route == Routes.R2P,
            onClick = { nav.navigateTab(Routes.R2P) }
        )
        Tab(
            label = R.string.nav_more,
            icon = { Icon(Icons.Default.Menu, contentDescription = null) },
            selected = route == Routes.MORE,
            onClick = { nav.navigateTab(Routes.MORE) }
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(
    label: Int,
    icon: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = { Text(stringResource(label)) }
    )
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavGraph(
    nav: NavHostController,
    padding: PaddingValues,
    apiClient: ApiClient,
    scaClient: ScaClient,
    sessionStore: SessionStore,
    offlineCache: OfflineCache,
) {
    NavHost(navController = nav, startDestination = Routes.DASHBOARD, modifier = Modifier.padding(padding)) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(apiClient = apiClient, offlineCache = offlineCache)
        }
        composable(Routes.PAYMENTS) {
            PaymentsScreen(apiClient = apiClient, scaClient = scaClient)
        }
        composable(Routes.R2P) {
            R2pInboxScreen(apiClient = apiClient, scaClient = scaClient)
        }
        composable(Routes.MORE) {
            MoreScreen(nav = nav, sessionStore = sessionStore)
        }
        composable(Routes.MANDATES) {
            MandatesScreen(apiClient = apiClient, scaClient = scaClient)
        }
        composable(Routes.BENEFICIARIES) {
            BeneficiariesScreen(apiClient = apiClient)
        }
        composable(Routes.CONSENTS) {
            ConsentsScreen(apiClient = apiClient)
        }
        composable(Routes.NOTIFICATIONS) {
            NotificationsScreen(apiClient = apiClient)
        }
        composable(Routes.PROFILE) {
            ProfileScreen(apiClient = apiClient, sessionStore = sessionStore)
        }
        composable(Routes.PRIVACY) {
            PrivacyScreen(apiClient = apiClient, scaClient = scaClient, sessionStore = sessionStore)
        }
    }
}

object Routes {
    const val DASHBOARD = "dashboard"
    const val PAYMENTS = "payments"
    const val R2P = "r2p"
    const val MORE = "more"
    const val MANDATES = "mandates"
    const val BENEFICIARIES = "beneficiaries"
    const val CONSENTS = "consents"
    const val NOTIFICATIONS = "notifications"
    const val PROFILE = "profile"
    const val PRIVACY = "privacy"
}
