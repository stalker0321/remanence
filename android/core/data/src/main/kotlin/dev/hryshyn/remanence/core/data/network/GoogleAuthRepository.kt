package dev.hryshyn.remanence.core.data.network

import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** Bare, bounded auth transport: no bearer injection, body logging or redirects. */
class GoogleAuthRepository(private val baseUrl: ApiBaseUrl) {
    private val client = HttpClientFactory.create()
    suspend fun request(path: String, body: JsonObject): JsonObject {
        require(path in setOf("options", "verify", "register"))
        val request = Request.Builder().url(baseUrl.resolve("v1/auth/google/$path"))
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.executeResponseWithCallLifetime(request) { response ->
            if (!response.isSuccessful) throw GoogleAuthRejected(response.code)
            if (response.body.contentType()?.subtype != "json") throw GoogleAuthRejected(0)
            val source = response.body.source(); val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, 96L * 1024 + 1 - buffer.size)
                if (buffer.size > 96L * 1024) throw GoogleAuthRejected(0)
                if (read == -1L) break
            }
            NetworkJson.decodeFromString<JsonObject>(buffer.readUtf8())
        }
    }
}
class GoogleAuthRejected(val status: Int) : Exception("Google authentication did not complete")
