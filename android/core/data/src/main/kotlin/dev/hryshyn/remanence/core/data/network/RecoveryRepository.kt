package dev.hryshyn.remanence.core.data.network

import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** Experimental bare transport; never logs bodies or follows redirects. */
class RecoveryRepository(private val baseUrl: ApiBaseUrl) {
    private val client = HttpClientFactory.create()
    suspend fun request(path: String, body: JsonObject? = null, token: String? = null): JsonObject {
        require(path in PATHS)
        val request = Request.Builder().url(baseUrl.resolve("v1/recovery/$path"))
            .header("Accept", "application/json")
        if (token != null) request.header("Authorization", "Bearer $token")
        if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
        return client.executeResponseWithCallLifetime(request.build()) { response ->
            if (!response.isSuccessful || response.body.contentType()?.subtype != "json") throw RecoveryRejectedException()
            // Stream a bounded response, including unknown Content-Length.
            val source = response.body.source(); val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, MAX_BYTES + 1 - buffer.size)
                if (buffer.size > MAX_BYTES) throw RecoveryRejectedException()
                if (read == -1L) break
            }
            val bytes = buffer.readByteArray()
            NetworkJson.decodeFromString<JsonObject>(bytes.toString(Charsets.UTF_8))
        }
    }
    private companion object {
        const val MAX_BYTES = 96L * 1024
        val PATHS = setOf("register/options", "register/verify", "setup/options", "setup/verify", "login/options", "login/verify", "record", "status")
    }
}
class RecoveryRejectedException : Exception("recovery request rejected")
