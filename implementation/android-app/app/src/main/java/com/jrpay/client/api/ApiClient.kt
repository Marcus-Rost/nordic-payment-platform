package com.jrpay.client.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Android counterpart of the web client's typed fetch wrapper.
 *
 * Responsibilities mirror admin-web / client-web:
 *   - inject the bearer JWT (from [BearerProvider]) on every call,
 *   - inject Idempotency-Key on mutating verbs,
 *   - inject Step-Up-Token when supplied,
 *   - decode 4xx / 5xx into [ProblemException],
 *   - emit a W3C Trace Context `traceparent` header.
 */
class ApiClient(
    @PublishedApi internal val baseUrl: String,
    @PublishedApi internal val bearerProvider: BearerProvider,
    @PublishedApi internal val httpClient: OkHttpClient = defaultHttp(),
    @PublishedApi internal val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {

    fun interface BearerProvider {
        fun bearer(): String?
    }

    suspend inline fun <reified Req, reified Res> post(
        path: String,
        body: Req? = null,
        stepUpToken: String? = null,
        idempotencyKey: String = newIdempotencyKey(),
    ): Res = call("POST", path, null, body, stepUpToken, idempotencyKey)

    suspend inline fun <reified Req, reified Res> patch(
        path: String,
        body: Req? = null,
        stepUpToken: String? = null,
        idempotencyKey: String = newIdempotencyKey(),
    ): Res = call("PATCH", path, null, body, stepUpToken, idempotencyKey)

    suspend inline fun <reified Res> delete(
        path: String,
        stepUpToken: String? = null,
        idempotencyKey: String = newIdempotencyKey(),
    ): Res = call("DELETE", path, null, Unit, stepUpToken, idempotencyKey)

    suspend inline fun <reified Res> get(
        path: String,
        query: Map<String, Any?> = emptyMap(),
    ): Res = call("GET", path, query, Unit, null, null)

    /**
     * Generic call. Marked `suspend` and dispatches on [Dispatchers.IO]; OkHttp
     * itself is blocking-by-design so we wrap here.
     */
    @PublishedApi
    internal suspend inline fun <reified Req, reified Res> call(
        method: String,
        path: String,
        query: Map<String, Any?>?,
        body: Req?,
        stepUpToken: String?,
        idempotencyKey: String?,
    ): Res = withContext(Dispatchers.IO) {
        val urlBuilder = (baseUrl.trimEnd('/') + path).toHttpUrl().newBuilder()
        query?.forEach { (k, v) ->
            when (v) {
                null -> Unit
                is Iterable<*> -> v.filterNotNull().forEach { urlBuilder.addQueryParameter(k, it.toString()) }
                else -> urlBuilder.addQueryParameter(k, v.toString())
            }
        }
        val builder = Request.Builder().url(urlBuilder.build())
        bearerProvider.bearer()?.let { builder.header("Authorization", "Bearer $it") }
        stepUpToken?.let { builder.header("Step-Up-Token", it) }
        if (method != "GET" && idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey)
        }
        builder.header("traceparent", traceparent())

        val requestBody = if (body != null && body !is Unit) {
            val bytes = json.encodeToString(body).toByteArray()
            bytes.toRequestBody("application/json".toMediaType())
        } else if (method != "GET") {
            ByteArray(0).toRequestBody("application/json".toMediaType())
        } else null

        builder.method(method, requestBody)

        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                val problem = runCatching { json.decodeFromString<Problem>(raw) }
                    .getOrElse {
                        Problem(
                            type = "urn:problem:unexpected",
                            title = response.message,
                            status = response.code,
                            detail = raw.takeIf { it.isNotBlank() },
                        )
                    }
                throw ProblemException(problem)
            }
            if (response.code == 204) {
                @Suppress("UNCHECKED_CAST")
                return@withContext Unit as Res
            }
            val raw = response.body?.string().orEmpty()
            json.decodeFromString(raw)
        }
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        @PublishedApi
        internal fun newIdempotencyKey(): String = UUID.randomUUID().toString()

        private val rng = SecureRandom()

        @PublishedApi
        internal fun traceparent(): String {
            val trace = ByteArray(16).also(rng::nextBytes).toHex()
            val span = ByteArray(8).also(rng::nextBytes).toHex()
            return "00-$trace-$span-01"
        }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }
    }
}
