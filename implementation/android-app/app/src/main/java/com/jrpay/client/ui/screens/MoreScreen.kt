package com.jrpay.client.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.jrpay.client.R
import com.jrpay.client.auth.SessionStore
import com.jrpay.client.ui.Routes

/**
 * "More" tab — secondary surfaces collapsed under a single tab so the
 * primary bottom-nav stays at four entries (a11y best practice).
 */
@Composable
fun MoreScreen(nav: NavHostController, sessionStore: SessionStore) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Item(R.string.mandates_title) { nav.navigate(Routes.MANDATES) }
        Item(R.string.beneficiaries_title) { nav.navigate(Routes.BENEFICIARIES) }
        Item(R.string.consents_title) { nav.navigate(Routes.CONSENTS) }
        Item(R.string.notifications_title) { nav.navigate(Routes.NOTIFICATIONS) }
        Item(R.string.profile_title) { nav.navigate(Routes.PROFILE) }
        Item(R.string.privacy_title) { nav.navigate(Routes.PRIVACY) }
        HorizontalDivider()
        Item(R.string.common_logout) { sessionStore.clear() }
    }
}

@Composable
private fun Item(labelRes: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(labelRes)) },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}
