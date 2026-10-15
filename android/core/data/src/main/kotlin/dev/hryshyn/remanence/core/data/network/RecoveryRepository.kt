package dev.hryshyn.remanence.core.data.network

import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/**
 * Recovery transport; never logs bodies or follows redirects.
 *
 * Two shapes share one bounded decoder: the bare client serves the
 * ANONYMOUS passkey login (options/verify) which must work with no
 * session, while signed-in ceremonies use [requestAuthenticated] over
 * the shared lease-tagged client below.
 */
class RecoveryRepository(
    private val baseUrl: ApiBaseUrl,
    private val authenticatedClient: OkHttpClient? = null,
    private val requestLeases: SessionRequestLeaseProvider? = null,
) {
    private val client = HttpClientFactory.create()
    suspend fun request(path: String, body: JsonObject? = null, token: String? = null): JsonObject {
        require(path in PATHS)
        val builder = PendingProfileHeaders.apply(
            Request.Builder().url(baseUrl.resolve("v1/recovery/$path"))
                .header("Accept", "application/json"),
        )
        val request = builder
        if (token != null) request.header("Authorization", "Bearer $token")
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        return client.executeResponseWithCallLifetime(request.build()) { response ->
            readJsonBounded(response)
        }
    }

    /**
     * Signed-in ceremony transport over the shared authenticated client.
     * The request carries the CEREMONY lease tag and NO captured bearer
     * string: final admission attaches the CURRENT same-lease bearer, and
     * a 401 refreshes once through the serialized boundary before the
     * follow-up re-admits the same lease. Owner/logout/replacement
     * fences refuse before any network; non-401 never replays.
     */
    suspend fun requestAuthenticated(lease: SessionRequestLease, path: String, body: JsonObject? = null): JsonObject {
        val authed = authenticatedClient ?: throw RecoveryRejectedException()
        val leases = requestLeases ?: throw RecoveryRejectedException()
        require(path in PATHS)
        if (!leases.isLive(lease)) throw RequestLeaseRejectedException()
        val builder = PendingProfileHeaders.apply(
            Request.Builder().url(baseUrl.resolve("v1/recovery/$path"))
                .header("Accept", "application/json"),
        )
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        val request = leases.tag(builder.build(), lease)
        return authed.executeResponseWithCallLifetime(request) { response ->
            readJsonBounded(response)
        }
    }

    private suspend fun readJsonBounded(response: okhttp3.Response): JsonObject {
        if (!response.isSuccessful || response.body.contentType()?.subtype != "json") throw RecoveryRejectedException()
        // Stream a bounded response, including unknown Content-Length.
        val source = response.body.source(); val buffer = okio.Buffer()
        while (true) {
            val read = source.read(buffer, MAX_BYTES + 1 - buffer.size)
            if (buffer.size > MAX_BYTES) throw RecoveryRejectedException()
            if (read == -1L) break
        }
        val bytes = buffer.readByteArray()
        return NetworkJson.decodeFromString<JsonObject>(bytes.toString(Charsets.UTF_8))
    }
    private companion object {
        const val MAX_BYTES = 96L * 1024
        val PATHS = setOf("register/options", "register/verify", "setup/options", "setup/verify", "login/options", "login/verify", "record", "status")
    }
}
class RecoveryRejectedException : Exception("recovery request rejected")
