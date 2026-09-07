package com.jrpay.client.ui.screens.mandates

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
import com.jrpay.client.sca.DlHash
import com.jrpay.client.sca.ScaClient
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class Mandate(
    val mandateId: String,
    val creditorId: String,
    val creditorName: String,
    val debtorAccountId: String,
    val capAmount: String? = null,
    val capCurrency: String? = null,
    val frequency: String? = null,
    val startDate: String? = null,
    val status: String,
)

@Serializable
data class MandateList(val items: List<Mandate>)

@Composable
fun MandatesScreen(apiClient: ApiClient, scaClient: ScaClient) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Mandate>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        runCatching { apiClient.get<MandateList>("/api/v1/customer/mandates") }
            .onSuccess { items = it.items }
            .onFailure { status = it.message }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.mandates_title), style = MaterialTheme.typography.headlineSmall)
        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn {
            items(items, key = { it.mandateId }) { m ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(m.creditorName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${m.capAmount ?: "—"} ${m.capCurrency ?: ""} · ${m.status}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (m.status == "ACTIVE") {
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        val inputs = DlHash.MandateAction(
                                            action = "TERMINATE",
                                            creditorId = m.creditorId,
                                            debtorAccountId = m.debtorAccountId,
                                        )
                                        val start = scaClient.start(inputs)
                                        val verify = scaClient.verify(
                                            start.scaSessionId,
                                            ScaClient.Proof.BankIdSimulated("000000"),
                                        )
                                        apiClient.delete<Unit>(
                                            "/api/v1/customer/mandates/${m.mandateId}",
                                            stepUpToken = verify.stepUpToken,
                                        )
                                    }
                                        .onSuccess { refresh++ }
                                        .onFailure { status = it.message }
                                }
                            }) {
                                Text(stringResource(R.string.mandates_terminate))
                            }
                        }
                    }
                }
            }
        }
    }
}
