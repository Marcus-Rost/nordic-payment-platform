package com.jrpay.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** JRPay theme — palette mirrors web client (jrpay-navy + jrpay-teal). */
private val Light = lightColorScheme(
    primary = Color(0xFF0d2247),
    onPrimary = Color.White,
    secondary = Color(0xFF0f766e),
    onSecondary = Color.White,
    error = Color(0xFFc62828),
    onError = Color.White,
    background = Color(0xFFf8fafc),
    surface = Color.White,
    onSurface = Color(0xFF0f172a),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF93c5fd),
    onPrimary = Color(0xFF0d2247),
    secondary = Color(0xFF2dd4bf),
    background = Color(0xFF0f172a),
    surface = Color(0xFF1e293b),
    onSurface = Color(0xFFe2e8f0),
)

@Composable
fun JrpayTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) Dark else Light
    MaterialTheme(colorScheme = colors, content = content)
}
