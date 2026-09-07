package com.jrpay.client.sca

import com.jrpay.client.api.ApiClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** SCA orchestration matching identity's /sca/start + /sca/{id}/verify. */
class ScaClient(private val api: ApiClient) {

    @Serializable
    data class StartRequest(
        val inputs: JsonObject,
        val factorHint: String? = null,
    )

    @Serializable
    data class StartResponse(
        val scaSessionId: String,
        val dl_hash: String,
        val challenge: Challenge,
        val exempt: Boolean = false,
        val stepUpToken: String? = null,
    )

    @Serializable
    data class Challenge(val type: String, val metadata: JsonObject? = null)

    @Serializable
    data class VerifyRequest(val proof: Proof)

    @Serializable
    sealed class Proof {
        @Serializable
        @SerialName("TOTP")
        data class Totp(val code: String) : Proof()

        @Serializable
        @SerialName("BANKID_SIMULATED")
        data class BankIdSimulated(val assertion: String) : Proof()
    }

    @Serializable
    data class VerifyResponse(val status: String, val stepUpToken: String)

    suspend fun start(inputs: DlHash.Inputs, factorHint: String? = null): StartResponse =
        api.post(
            "/sca/start",
            body = StartRequest(toJsonObject(inputs.toMap()), factorHint),
        )

    suspend fun verify(sessionId: String, proof: Proof): VerifyResponse =
        api.post("/sca/${sessionId}/verify", body = VerifyRequest(proof))

    private fun toJsonObject(map: Map<String, Any?>): JsonObject =
        JsonObject(map.mapValues { (_, v) -> toJsonElement(v) })

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is JsonElement -> value
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            toJsonObject(value as Map<String, Any?>)
        }
        else -> JsonPrimitive(value.toString())
    }
}
