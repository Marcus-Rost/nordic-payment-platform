package com.jrpay.client.ui.screens.profile

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
import androidx.compose.ui.unit.dp
import com.jrpay.client.R
import com.jrpay.client.api.ApiClient
import com.jrpay.client.auth.SessionStore
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val customerId: String,
    val name: String,
    val email: String,
    val phone: String? = null,
    val address: String? = null,
    val language: String? = null,
)

@Serializable
data class ProfilePatch(val phone: String? = null, val address: String? = null, val language: String? = null)

@Composable
fun ProfileScreen(apiClient: ApiClient, sessionStore: SessionStore) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<Profile?>(null) }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { apiClient.get<Profile>("/api/v1/customer/profile") }
            .onSuccess {
                profile = it
                phone = it.phone ?: ""
                address = it.address ?: ""
            }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineSmall)
        profile?.let { p ->
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Text(p.email, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Mobile") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Address") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            apiClient.patch<ProfilePatch, Profile>(
                                "/api/v1/customer/profile",
                                body = ProfilePatch(phone = phone, address = address),
                            )
                        }
                            .onSuccess { profile = it; status = "Saved" }
                            .onFailure { status = it.message }
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text(stringResource(R.string.common_save)) }
        }
        Button(
            onClick = { sessionStore.clear() },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text(stringResource(R.string.common_logout)) }
        status?.let { Text(it) }
    }
}
