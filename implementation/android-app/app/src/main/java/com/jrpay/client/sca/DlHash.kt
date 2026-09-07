package com.jrpay.client.sca

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Dynamic-linking hash — byte-identical to the Java services and the web
 * client (admin-web + client-web) per gui/architecture/DYNAMIC_LINKING.md.
 *
 * Algorithm: SHA-256 of canonical JSON
 *   - members lex-sorted by UTF-16 code units
 *   - strings UTF-8 NFC
 *   - no whitespace
 *   - arrays preserve insertion order
 *   - amounts MUST be strings (numbers forbidden)
 * Output: lowercase hex of the 32-byte digest.
 *
 * The Kotlin implementation here uses [LinkedHashMap] for arbitrary maps
 * (so callers can build inputs in any order) and sorts at canonicalisation
 * time. Sealed input wrappers exist for the four customer classes.
 */
object DlHash {

    sealed interface Inputs {
        fun toMap(): Map<String, Any?>
    }

    data class PaymentInitiation(
        val creditorIban: String,
        val debtorAccountId: String,
        val amount: String,
        val currency: String,
        val reference: String? = null,
        val executionDate: String? = null,
    ) : Inputs {
        override fun toMap(): Map<String, Any?> = buildMap {
            put("amount", amount)
            put("class", "payment-initiation")
            put("creditorIban", creditorIban)
            put("currency", currency)
            put("debtorAccountId", debtorAccountId)
            executionDate?.let { put("executionDate", it) }
            reference?.let { put("reference", it) }
        }
    }

    data class MandateAction(
        val action: String, // CREATE | AMEND | TERMINATE
        val creditorId: String,
        val debtorAccountId: String,
        val capAmount: String? = null,
        val capCurrency: String? = null,
    ) : Inputs {
        override fun toMap(): Map<String, Any?> = buildMap {
            put("action", action)
            capAmount?.let { put("capAmount", it) }
            capCurrency?.let { put("capCurrency", it) }
            put("class", "mandate-action")
            put("creditorId", creditorId)
            put("debtorAccountId", debtorAccountId)
        }
    }

    data class ConsentGrant(
        val consentType: String, // AISP | PISP | CBPII | VRP
        val tppId: String,
        val expiresAt: String,
        val accountIds: List<String>? = null,
    ) : Inputs {
        override fun toMap(): Map<String, Any?> = buildMap {
            if (!accountIds.isNullOrEmpty()) put("accountIds", accountIds)
            put("class", "consent-grant")
            put("consentType", consentType)
            put("expiresAt", expiresAt)
            put("tppId", tppId)
        }
    }

    data class GdprAction(
        val action: String, // EXPORT | ERASURE_REQUEST
        val customerId: String,
        val notesHash: String,
    ) : Inputs {
        override fun toMap(): Map<String, Any?> = mapOf(
            "action" to action,
            "class" to "gdpr-action",
            "customerId" to customerId,
            "notesHash" to notesHash,
        )
    }

    data class Result(val canonical: String, val hex: String)

    fun hash(inputs: Inputs): Result = hash(inputs.toMap())

    fun hash(map: Map<String, Any?>): Result {
        val canonical = canonicalise(map)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return Result(canonical, digest.toHexLower())
    }

    fun sha256HexNfc(value: String): String {
        val nfc = Normalizer.normalize(value, Normalizer.Form.NFC)
        val digest = MessageDigest.getInstance("SHA-256").digest(nfc.toByteArray(Charsets.UTF_8))
        return digest.toHexLower()
    }

    private fun canonicalise(value: Any?): String = when (value) {
        null -> "null"
        is String -> canonicaliseString(value)
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        is List<*> -> value.joinToString(",", prefix = "[", postfix = "]") { canonicalise(it) }
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val entries = (value as Map<String, Any?>).entries
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER.reversed().reversed()) { it.key })
                .sortedBy { it.key } // stable lex (UTF-16 code units; Kotlin compareTo on String is utf-16)
            entries.joinToString(",", prefix = "{", postfix = "}") { (k, v) ->
                "${canonicaliseString(k)}:${canonicalise(v)}"
            }
        }
        else -> "null"
    }

    private fun canonicaliseString(value: String): String {
        val nfc = Normalizer.normalize(value, Normalizer.Form.NFC)
        val sb = StringBuilder("\"")
        for (ch in nfc) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch.code == 0x08 -> sb.append("\\b")
                ch.code == 0x0c -> sb.append("\\f")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun ByteArray.toHexLower(): String =
        joinToString("") { "%02x".format(it) }
}
