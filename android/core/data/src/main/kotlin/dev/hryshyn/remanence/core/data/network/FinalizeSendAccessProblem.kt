package dev.hryshyn.remanence.core.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Finalizer-only authority. Never add these codes to draft/blob classifiers. */
internal enum class FinalizeSendAccessProblem { LIMIT, UNAVAILABLE }

internal fun finalizeSendAccessProblem(
    text: String, status: Int, allowMinimalPending: Boolean,
): FinalizeSendAccessProblem? = try {
    val row = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException()
    val code = row.strictString("code")
    val kind = when {
        status == 409 && code == "SEND_LIMIT_REACHED" -> FinalizeSendAccessProblem.LIMIT
        status == 503 && code == "SEND_ACCESS_UNAVAILABLE" -> FinalizeSendAccessProblem.UNAVAILABLE
        else -> throw IllegalArgumentException()
    }
    require(row.strictInt("status") == status)
    if (allowMinimalPending && row.keys == setOf("type", "title", "status", "code")) {
        require(row.strictString("type") == "about:blank" && row.strictString("title") == code)
    } else {
        row.exactObject("type", "title", "status", "code", "detail", "request_id", "retryable")
        fun bounded(key: String, max: Int) {
            val value = row.strictString(key)
            require(value.isNotEmpty() && value.length <= max &&
                value.none { it.code < 32 || it.code == 127 })
        }
        bounded("type", 256); bounded("title", 256); bounded("detail", 1024)
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .matches(row.strictString("request_id")))
        require(row.strictBoolean("retryable") == (kind == FinalizeSendAccessProblem.UNAVAILABLE))
    }
    kind
} catch (_: IllegalArgumentException) { null }
