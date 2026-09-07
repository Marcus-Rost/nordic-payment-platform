package com.jrpay.client.ui.screens.privacy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.jrpay.client.auth.SessionStore
import com.jrpay.client.sca.DlHash
import com.jrpay.client.sca.ScaClient
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class ExportRequest(val format: String)

@Serializable
data class ExportAck(val requestId: String, val status: String)

@Serializable
data class ErasureRequest(val reason: String, val confirmed: Boolean)

@Composable
fun PrivacyScreen(
    apiClient: ApiClient,
    scaClient: ScaClient,
    sessionStore: SessionStore,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.headlineSmall)

        Button(
            onClick = {
                scope.launch {
                    runCatching {
                        apiClient.post<ExportRequest, ExportAck>(
                            "/api/v1/customer/gdpr/exports",
                            ExportRequest("JSON_SIGNED"),
                        )
                    }
                        .onSuccess { status = "Export queued: ${it.requestId}" }
                        .onFailure { status = it.message }
                }
            },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text(stringResource(R.string.privacy_request_export)) }

        Button(
            onClick = {
                val customerId = sessionStore.state.value?.claims?.jrpay_uid ?: return@Button
                scope.launch {
                    runCatching {
                        val notesHash = DlHash.sha256HexNfc("Erasure requested via Android client")
                        val inputs = DlHash.GdprAction(
                            action = "ERASURE_REQUEST",
                            customerId = customerId,
                            notesHash = notesHash,
                        )
                        val start = scaClient.start(inputs)
                        val verify = scaClient.verify(
                            start.scaSessionId,
                            ScaClient.Proof.BankIdSimulated("000000"),
                        )
                        apiClient.post<ErasureRequest, Unit>(
                            "/api/v1/customer/gdpr/erasure",
                            ErasureRequest(reason = "Erasure via mobile app", confirmed = true),
                            stepUpToken = verify.stepUpToken,
                        )
                    }
                        .onSuccess { status = "Erasure requested" }
                        .onFailure { status = it.message }
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(stringResource(R.string.privacy_request_erasure)) }

        status?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
    }
}
