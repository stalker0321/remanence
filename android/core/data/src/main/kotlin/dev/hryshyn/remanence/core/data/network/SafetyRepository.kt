package dev.hryshyn.remanence.core.data.network

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer

@Serializable
data class SafetyState(val terms_version: String, val terms_accepted: Boolean, val blocked_handles: List<String>)

@Serializable
private data class SafetyUpdate(val handle: String? = null, val reason: String? = null, val version: String? = null)

/** Same authenticated refreshing transport and captured owner lease as capsule operations. */
class SafetyRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
    private val leases: SessionRequestLeaseProvider,
) {
    private val requestJson = Json { encodeDefaults = false; explicitNulls = false }
    suspend fun state(): SafetyState? {
        val lease = leases.capture() ?: return null
        val request = leases.tag(Request.Builder().url(baseUrl.resolve("v1/me/safety")).get().build(), lease)
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                if (response.code != 200 || !leases.isLive(lease) || response.body.contentType()?.subtype != "json") return@executeResponseWithCallLifetime null
                val raw = response.body.readSafetyBody() ?: return@executeResponseWithCallLifetime null
                NetworkJson.decodeFromString<SafetyState>(raw).takeIf {
                    leases.isLive(lease) && it.terms_version == "2026-10-11" && it.blocked_handles.size <= 256 &&
                        it.blocked_handles.all { handle -> handle.matches(Regex("[a-z0-9_.]{3,30}")) }
                }
            }
        } catch (_: IOException) { null }
        catch (_: SerializationException) { null }
    }

    suspend fun acceptTerms(): Boolean = update("terms", SafetyUpdate(version = "2026-10-11"), "ACCEPTED")
    suspend fun block(handle: String): Boolean = update("blocks", SafetyUpdate(handle = handle), "BLOCKED")
    suspend fun unblock(handle: String): Boolean = update("blocks/remove", SafetyUpdate(handle = handle), "UNBLOCKED")
    suspend fun report(handle: String, reason: String): Boolean {
        require(reason in listOf("SPAM", "HARASSMENT", "INAPPROPRIATE_CONTENT", "OTHER"))
        return update("reports", SafetyUpdate(handle, reason), "RECEIVED")
    }

    private suspend fun update(path: String, payload: SafetyUpdate, expected: String): Boolean {
        val lease = leases.capture() ?: return false
        val body = requestJson.encodeToString(payload).toRequestBody("application/json".toMediaType())
        val request = leases.tag(Request.Builder().url(baseUrl.resolve("v1/me/$path")).post(body).build(), lease)
        return try {
            client.executeResponseWithCallLifetime(request) { response ->
                if (response.code !in 200..201 || !leases.isLive(lease) || response.body.contentType()?.subtype != "json") return@executeResponseWithCallLifetime false
                val raw = response.body.readSafetyBody() ?: return@executeResponseWithCallLifetime false
                val result = NetworkJson.decodeFromString<Map<String, String>>(raw)
                leases.isLive(lease) && result == mapOf("status" to expected)
            }
        } catch (_: IOException) { false }
        catch (_: SerializationException) { false }
    }
}

private fun ResponseBody.readSafetyBody(): String? {
    val limit = 32 * 1024L
    if (contentLength() > limit) return null
    val buffer = Buffer()
    val source = source()
    while (true) {
        val read = source.read(buffer, limit + 1 - buffer.size)
        if (buffer.size > limit) return null
        if (read == -1L) return buffer.readUtf8()
    }
}
