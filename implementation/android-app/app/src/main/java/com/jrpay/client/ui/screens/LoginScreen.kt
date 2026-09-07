package com.jrpay.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jrpay.client.JrpayApplication
import com.jrpay.client.R
import com.jrpay.client.auth.OAuthClient
import com.jrpay.client.auth.PkceFlow

@Composable
fun LoginScreen(oAuthClient: OAuthClient) {
    val context = LocalContext.current
    val callbackHandler = remember(context) {
        JrpayApplication.get(context).run {
            com.jrpay.client.auth.OAuthCallbackHandler(this.oAuthClient, this.sessionStore)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = {
                val pkce = PkceFlow.new()
                callbackHandler.stash(pkce)
                oAuthClient.launchAuthorize(context, pkce)
            },
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text(stringResource(R.string.login_continue_with_bankid))
        }
        Text(
            text = stringResource(R.string.login_sca_notice),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
