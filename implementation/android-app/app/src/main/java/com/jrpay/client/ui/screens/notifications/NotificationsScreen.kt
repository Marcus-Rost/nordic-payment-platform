package com.jrpay.client.ui.screens.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jrpay.client.R
import com.jrpay.client.api.ApiClient
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class Notif(
    val notificationId: String,
    val category: String,
    val title: String,
    val body: String,
    val severity: String,
    val receivedAt: String,
    val read: Boolean = false,
)

@Serializable
data class NotifList(val items: List<Notif>)

@Composable
fun NotificationsScreen(apiClient: ApiClient) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Notif>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        runCatching { apiClient.get<NotifList>("/api/v1/customer/notifications") }
            .onSuccess { items = it.items }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.notifications_title), style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = {
            scope.launch {
                runCatching {
                    apiClient.post<Unit, Unit>("/api/v1/customer/notifications/read-all", Unit)
                }.onSuccess { refresh++ }
            }
        }) { Text(stringResource(R.string.notifications_mark_all_read)) }

        LazyColumn {
            items(items, key = { it.notificationId }) { n ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(n.title, style = MaterialTheme.typography.titleMedium)
                        Text(n.body, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${n.severity} · ${n.receivedAt}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
