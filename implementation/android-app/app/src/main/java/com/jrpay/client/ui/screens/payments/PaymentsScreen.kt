package com.jrpay.client.ui.screens.payments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.jrpay.client.R
import com.jrpay.client.api.ApiClient
import com.jrpay.client.api.ProblemException
import com.jrpay.client.sca.DlHash
import com.jrpay.client.sca.ScaClient
import com.jrpay.client.ui.screens.dashboard.AccountList
import com.jrpay.client.ui.screens.dashboard.AccountSummary
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class VopRequest(val creditorIban: String, val creditorName: String)

@Serializable
data class VopResponse(val result: String, val actualNameMasked: String? = null)

@Serializable
data class PaymentRequest(
    val debtorAccountId: String,
    val creditorIban: String,
    val creditorName: String,
    val amount: String,
    val currency: String,
    val reference: String? = null,
    val executionMode: String = "INSTANT",
    val executionDate: String? = null,
)

@Serializable
data class PaymentAck(val paymentId: String, val status: String)

/**
 * UC-G05 / UC-G07 — instant payment + scheduled payment with VoP.
 *
 * Submission runs the SCA flow:
 *   1. compute paymentInitiationDlHash
 *   2. POST /sca/start → SCA modal
 *   3. POST /sca/{id}/verify → step-up token
 *   4. POST /api/v1/customer/payments with the token
 */
@Composable
fun PaymentsScreen(apiClient: ApiClient, scaClient: ScaClient) {
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<AccountSummary>>(emptyList()) }
    var debtor by remember { mutableStateOf<AccountSummary?>(null) }
    var creditorIban by remember { mutableStateOf("") }
    var creditorName by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var vopBadge by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { apiClient.get<AccountList>("/api/v1/customer/accounts") }
            .onSuccess {
                accounts = it.items
                debtor = it.items.firstOrNull()
            }
            .onFailure { status = it.message }
    }

    // VoP debounce: when iban + name + amount stable, run.
    LaunchedEffect(creditorIban, creditorName, amount) {
        if (creditorIban.isBlank() || creditorName.isBlank() || amount.isBlank()) {
            vopBadge = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(400)
        runCatching {
            apiClient.post<VopRequest, VopResponse>(
                "/api/v1/customer/payments/verify-payee",
                VopRequest(creditorIban.trim(), creditorName.trim()),
            )
        }
            .onSuccess { vopBadge = it.result }
            .onFailure { e ->
                vopBadge = when {
                    e is ProblemException && e.isVopUnavailable -> "UNAVAILABLE"
                    else -> null
                }
            }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.payments_new_instant), style = MaterialTheme.typography.titleLarge)

        debtor?.let { d ->
            Text("${stringResource(R.string.payments_from_account)}: ${d.nickname ?: d.type} (${d.currency})")
        }

        OutlinedTextField(
            value = creditorIban,
            onValueChange = { creditorIban = it.uppercase() },
            label = { Text(stringResource(R.string.payments_creditor_iban)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = creditorName,
            onValueChange = { creditorName = it },
            label = { Text(stringResource(R.string.payments_creditor_name)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text(stringResource(R.string.payments_amount)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = reference,
            onValueChange = { reference = it },
            label = { Text(stringResource(R.string.payments_reference)) },
            modifier = Modifier.fillMaxWidth(),
        )

        vopBadge?.let { result ->
            val text = when (result) {
                "MATCH" -> stringResource(R.string.payments_vop_match)
                "CLOSE_MATCH" -> stringResource(R.string.payments_vop_close)
                "MISMATCH", "NO_MATCH" -> stringResource(R.string.payments_vop_mismatch)
                "UNAVAILABLE" -> stringResource(R.string.payments_vop_unavailable)
                else -> result
            }
            val color = when (result) {
                "MATCH" -> MaterialTheme.colorScheme.secondary
                "CLOSE_MATCH", "UNAVAILABLE" -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            }
            Text(text, color = color)
        }

        val canSubmit = debtor != null &&
            creditorIban.isNotBlank() &&
            creditorName.isNotBlank() &&
            (amount.toDoubleOrNull() ?: 0.0) > 0.0 &&
            vopBadge != "MISMATCH" &&
            vopBadge != "NO_MATCH" &&
            !submitting

        Button(
            onClick = {
                val d = debtor ?: return@Button
                submitting = true
                scope.launch {
                    runCatching {
                        val inputs = DlHash.PaymentInitiation(
                            creditorIban = creditorIban.trim(),
                            debtorAccountId = d.accountId,
                            amount = amount,
                            currency = d.currency,
                            reference = reference.takeIf { it.isNotBlank() },
                        )
                        val start = scaClient.start(inputs)
                        val verify = scaClient.verify(
                            start.scaSessionId,
                            ScaClient.Proof.BankIdSimulated("000000"),
                        )
                        apiClient.post<PaymentRequest, PaymentAck>(
                            "/api/v1/customer/payments",
                            PaymentRequest(
                                debtorAccountId = d.accountId,
                                creditorIban = creditorIban.trim(),
                                creditorName = creditorName.trim(),
                                amount = amount,
                                currency = d.currency,
                                reference = reference.takeIf { it.isNotBlank() },
                                executionMode = "INSTANT",
                            ),
                            stepUpToken = verify.stepUpToken,
                        )
                    }
                        .onSuccess { ack ->
                            status = "${ack.status} (${ack.paymentId})"
                            creditorIban = ""; creditorName = ""; amount = ""; reference = ""
                            vopBadge = null
                        }
                        .onFailure { e ->
                            status = (e as? ProblemException)?.problem?.title ?: e.message
                        }
                    submitting = false
                }
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.payments_submit_instant))
        }

        status?.let { Text(it) }
    }
}
