package com.jrpay.client.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * RFC 9457 Problem Details. Field set deliberately matches the web client's
 * `ProblemDetail` so server contracts stay platform-agnostic.
 */
@Serializable
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val extensions: JsonObject? = null,
)

class ProblemException(val problem: Problem) : RuntimeException(
    "${problem.type}: ${problem.title}${if (problem.detail != null) " — ${problem.detail}" else ""}"
) {
    val isStepUpRequired: Boolean get() = problem.type == "urn:problem:step-up-required"
    val isDynamicLinkingMismatch: Boolean get() = problem.type == "urn:problem:dynamic-linking-mismatch"
    val isVopMismatch: Boolean get() = problem.type == "urn:problem:vop-mismatch"
    val isVopUnavailable: Boolean get() = problem.type == "urn:problem:vop-unavailable"
    val isFraudHold: Boolean get() = problem.type == "urn:problem:fraud-hold"
    val isSanctionsBlock: Boolean get() = problem.type == "urn:problem:sanctions-block"
    val isMandateCapExceeded: Boolean get() = problem.type == "urn:problem:mandate-cap-exceeded"
    val isInsufficientFunds: Boolean get() = problem.type == "urn:problem:insufficient-funds"
    val isAccountFrozen: Boolean get() = problem.type == "urn:problem:account-frozen"
}
