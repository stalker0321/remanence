package dev.hryshyn.remanence.core.data.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

/** Mutate a valid wire fixture, not a model of the production classifier. */
internal fun malformedFinalizeProblems(valid: String): Map<String, String> {
    val original = Json.parseToJsonElement(valid).jsonObject
    fun replace(key: String, raw: String): String =
        JsonObject(original + (key to Json.parseToJsonElement(raw))).toString()
    return linkedMapOf(
        "empty object" to "{}",
        "missing code" to JsonObject(original - "code").toString(),
        "missing status" to JsonObject(original - "status").toString(),
        "null root" to "null",
        "array root" to "[]",
        "string root" to "\"not a problem\"",
        "boolean root" to "true",
        "number root" to "409",
        "null code" to replace("code", "null"),
        "number code" to replace("code", "409"),
        "boolean code" to replace("code", "true"),
        "object code" to replace("code", "{}"),
        "array code" to replace("code", "[]"),
        "null status" to replace("status", "null"),
        "quoted status" to replace("status", "\"${original.getValue("status")}\""),
        "fractional status" to replace("status", "${original.getValue("status")}.0"),
        "boolean status" to replace("status", "true"),
        "object status" to replace("status", "{}"),
        "array status" to replace("status", "[]"),
        "null title" to replace("title", "null"),
        "extra key" to replace("unexpected", "true"),
    ).apply {
        if ("retryable" in original) {
            put("null retryable", replace("retryable", "null"))
            put("quoted retryable", replace("retryable", "\"${original.getValue("retryable")}\""))
            put("number retryable", replace("retryable", "1"))
        }
    }
}

/** Hold the real repository's transport, cancel its caller, then release the IO. */
internal suspend fun assertFinalizeTransportCancellation(call: suspend (OkHttpClient) -> Any) = coroutineScope {
    val entered = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val client = HttpClientFactory.create().newBuilder().addInterceptor {
        entered.complete(Unit)
        check(release.await(10, TimeUnit.SECONDS))
        throw IOException("synthetic cancelled finalization")
    }.build()
    val job = async { call(client) }
    try {
        entered.await()
        job.cancel()
    } finally {
        release.countDown()
    }
    job.join()
    assertTrue(job.isCancelled)
}
