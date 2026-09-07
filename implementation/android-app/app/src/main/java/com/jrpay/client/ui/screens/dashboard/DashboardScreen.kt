package com.jrpay.client.ui.screens.dashboard

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jrpay.client.R
import com.jrpay.client.api.ApiClient
import com.jrpay.client.api.ProblemException
import com.jrpay.client.data.OfflineCache
import com.jrpay.client.ui.components.UiState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class AccountSummary(
    val accountId: String,
    val iban: String? = null,
    val ibanMasked: String? = null,
    val nickname: String? = null,
    val type: String,
    val currency: String,
    val bookedBalance: String? = null,
    val availableBalance: String? = null,
    val status: String,
)

@Serializable
data class AccountList(val items: List<AccountSummary>)

@Composable
fun DashboardScreen(apiClient: ApiClient, offlineCache: OfflineCache) {
    var state by remember { mutableStateOf<UiState<AccountList>>(UiState.Loading) }
    val json = remember { Json { ignoreUnknownKeys = true } }

    LaunchedEffect(Unit) {
        // Render cached snapshot first if available (UC-G28).
        offlineCache.get("dashboard:accounts")?.let {
            runCatching { json.decodeFromString<AccountList>(it.payload) }
                .onSuccess { cached -> state = UiState.Ready(cached) }
        }
        // Then refresh from server.
        runCatching { apiClient.get<AccountList>("/api/v1/customer/accounts") }
            .onSuccess {
                state = UiState.Ready(it)
                offlineCache.put("dashboard:accounts", json.encodeToString(it))
            }
            .onFailure { e ->
                if (state !is UiState.Ready) {
                    state = UiState.Error(
                        if (e is ProblemException) e.problem.title
                        else e.message ?: "Network error"
                    )
                }
            }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.dashboard_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        when (val s = state) {
            UiState.Loading -> Text(stringResource(R.string.common_loading))
            is UiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
            is UiState.Ready -> AccountsList(s.value.items)
        }
    }
}

@Composable
private fun AccountsList(items: List<AccountSummary>) {
    if (items.isEmpty()) {
        Text(stringResource(R.string.common_no_results))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.accountId }) { account ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        account.nickname ?: account.type,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        account.ibanMasked ?: account.iban ?: "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.accounts_available))
                        Text(
                            "${account.availableBalance ?: "0.00"} ${account.currency}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
