package com.jrpay.client.ui.screens.consents

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
data class Consent(
    val consentId: String,
    val tppName: String,
    val consentType: String,
    val expiresAt: String,
    val status: String,
)

@Serializable
data class ConsentList(val items: List<Consent>)

@Composable
fun ConsentsScreen(apiClient: ApiClient) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Consent>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        runCatching { apiClient.get<ConsentList>("/api/v1/customer/consents") }
            .onSuccess { items = it.items }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.consents_title), style = MaterialTheme.typography.headlineSmall)
        LazyColumn {
            items(items, key = { it.consentId }) { c ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(c.tppName, style = MaterialTheme.typography.titleMedium)
                        Text("${c.consentType} · ${c.expiresAt}", style = MaterialTheme.typography.bodySmall)
                        if (c.status == "ACTIVE") {
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        apiClient.delete<Unit>("/api/v1/customer/consents/${c.consentId}")
                                    }.onSuccess { refresh++ }
                                }
                            }) {
                                Text(stringResource(R.string.consents_revoke))
                            }
                        }
                    }
                }
            }
        }
    }
}
