package com.jrpay.client.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * UC-G27 — biometric cold-start unlock.
 *
 * The customer's session is encrypted at rest by [SecureTokenStore]; we
 * gate access to the in-memory session by a successful biometric prompt
 * the first time the app process starts (rotation / configuration changes
 * do NOT re-prompt).
 *
 * If no biometric is enrolled, fall back to BankID (the negative button).
 */
object BiometricUnlock {

    enum class Result { SUCCESS, FAILED, FALL_BACK_TO_BANKID, UNAVAILABLE }

    fun isAvailable(activity: FragmentActivity): Boolean {
        val bm = BiometricManager.from(activity)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    suspend fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButton: String,
    ): Result {
        if (!isAvailable(activity)) return Result.UNAVAILABLE
        return suspendCancellableCoroutine { cont ->
            val executor = androidx.core.content.ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    cont.resume(Result.SUCCESS)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val r = when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> Result.FALL_BACK_TO_BANKID
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> Result.FAILED
                        else -> Result.FAILED
                    }
                    cont.resume(r)
                }
                override fun onAuthenticationFailed() { /* prompt remains open */ }
            }
            val prompt = BiometricPrompt(activity, executor, callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negativeButton)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info)
        }
    }
}
