package com.jrpay.client.ui.screens.beneficiaries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
data class Beneficiary(
    val beneficiaryId: String,
    val name: String,
    val iban: String,
    val trusted: Boolean = false,
    val vopBound: Boolean = false,
)

@Serializable
data class BeneficiaryList(val items: List<Beneficiary>)

@Serializable
data class TrustToggle(val trusted: Boolean)

@Composable
fun BeneficiariesScreen(apiClient: ApiClient) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Beneficiary>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        runCatching { apiClient.get<BeneficiaryList>("/api/v1/customer/beneficiaries") }
            .onSuccess { items = it.items }
            .onFailure { status = it.message }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.beneficiaries_title), style = MaterialTheme.typography.headlineSmall)
        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.beneficiaryId }) { b ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(b.name, style = MaterialTheme.typography.titleMedium)
                        Text(b.iban, style = MaterialTheme.typography.bodySmall)
                        Row(modifier = Modifier.padding(top = 4.dp)) {
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        apiClient.post<TrustToggle, Unit>(
                                            "/api/v1/customer/beneficiaries/${b.beneficiaryId}/trust",
                                            body = TrustToggle(!b.trusted),
                                        )
                                    }
                                        .onSuccess { refresh++ }
                                        .onFailure { status = it.message }
                                }
                            }) {
                                Text(
                                    stringResource(
                                        if (b.trusted) R.string.beneficiaries_untrust
                                        else R.string.beneficiaries_trust
                                    )
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        apiClient.delete<Unit>("/api/v1/customer/beneficiaries/${b.beneficiaryId}")
                                    }
                                        .onSuccess { refresh++ }
                                        .onFailure { status = it.message }
                                }
                            }) {
                                Text(stringResource(R.string.common_delete))
                            }
                        }
                    }
                }
            }
        }
    }
}
