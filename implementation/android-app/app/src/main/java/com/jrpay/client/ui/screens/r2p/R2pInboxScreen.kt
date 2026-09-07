package com.jrpay.client.ui.screens.r2p

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
data class R2pRequest(
    val requestId: String,
    val creditorName: String,
    val creditorIban: String,
    val debtorAccountId: String,
    val amount: String,
    val currency: String,
    val reference: String? = null,
    val dueDate: String? = null,
    val status: String,
)

@Serializable
data class R2pList(val items: List<R2pRequest>)

@Composable
fun R2pInboxScreen(apiClient: ApiClient, scaClient: ScaClient) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<R2pRequest>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        runCatching { apiClient.get<R2pList>("/api/v1/customer/r2p", mapOf("status" to listOf("PENDING"))) }
            .onSuccess { items = it.items }
            .onFailure { status = it.message }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.r2p_title), style = MaterialTheme.typography.headlineSmall)
        if (items.isEmpty()) Text(stringResource(R.string.r2p_no_requests))
        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn {
            items(items, key = { it.requestId }) { r ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(r.creditorName, style = MaterialTheme.typography.titleMedium)
                        Text("${r.amount} ${r.currency}", style = MaterialTheme.typography.titleLarge)
                        r.dueDate?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Button(onClick = {
                                scope.launch {
                                    runCatching {
                                        val inputs = DlHash.PaymentInitiation(
                                            creditorIban = r.creditorIban,
                                            debtorAccountId = r.debtorAccountId,
                                            amount = r.amount,
                                            currency = r.currency,
                                            reference = r.reference,
                                        )
                                        val start = scaClient.start(inputs)
                                        val verify = scaClient.verify(
                                            start.scaSessionId,
                                            ScaClient.Proof.BankIdSimulated("000000"),
                                        )
                                        apiClient.post<Unit, Unit>(
                                            "/api/v1/customer/r2p/${r.requestId}/accept",
                                            body = Unit,
                                            stepUpToken = verify.stepUpToken,
                                        )
                                    }
                                        .onSuccess { refresh++ }
                                        .onFailure { status = it.message }
                                }
                            }) { Text(stringResource(R.string.r2p_accept)) }

                            TextButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        apiClient.post<Map<String, String>, Unit>(
                                            "/api/v1/customer/r2p/${r.requestId}/decline",
                                            body = mapOf("reason" to "OTHER"),
                                        )
                                    }
                                        .onSuccess { refresh++ }
                                        .onFailure { status = it.message }
                                }
                            }) { Text(stringResource(R.string.r2p_decline)) }
                        }
                    }
                }
            }
        }
    }
}
