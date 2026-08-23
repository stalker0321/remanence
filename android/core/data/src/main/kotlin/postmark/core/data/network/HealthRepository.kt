package postmark.core.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.coroutines.executeAsync

enum class HealthFailure {
    NETWORK,
    HTTP,
    INVALID_RESPONSE,
    UNHEALTHY,
}

sealed interface HealthCheckResult {
    data object Available : HealthCheckResult

    data class Unavailable(
        val reason: HealthFailure,
        val httpStatus: Int? = null,
    ) : HealthCheckResult
}

class HealthRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    suspend fun check(): HealthCheckResult {
        val request = Request.Builder()
            .url(baseUrl.resolve("healthz"))
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            client.newCall(request).executeAsync().use { response ->
                interpret(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            HealthCheckResult.Unavailable(HealthFailure.NETWORK)
        }
    }

    private fun interpret(response: Response): HealthCheckResult {
        if (!response.isSuccessful) {
            return HealthCheckResult.Unavailable(HealthFailure.HTTP, response.code)
        }
        val body = response.body
        val contentType = body.contentType()
        if (contentType == null || contentType.type != "application" || contentType.subtype != "json") {
            return HealthCheckResult.Unavailable(HealthFailure.INVALID_RESPONSE)
        }
        val bytes = readBounded(body)
            ?: return HealthCheckResult.Unavailable(HealthFailure.INVALID_RESPONSE)
        val text = decodeUtf8(bytes)
            ?: return HealthCheckResult.Unavailable(HealthFailure.INVALID_RESPONSE)
        val dto = try {
            NetworkJson.decodeFromString<HealthResponseDto>(text)
        } catch (_: SerializationException) {
            return HealthCheckResult.Unavailable(HealthFailure.INVALID_RESPONSE)
        }
        return if (dto.status == "ok") {
            HealthCheckResult.Available
        } else {
            HealthCheckResult.Unavailable(HealthFailure.UNHEALTHY)
        }
    }

    private fun readBounded(body: ResponseBody): ByteArray? {
        val contentLength = body.contentLength()
        if (contentLength > MAX_BODY_BYTES) {
            return null
        }
        val collected = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        body.byteStream().use { stream ->
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) {
                    break
                }
                if (collected.size() + read > MAX_BODY_BYTES) {
                    return null
                }
                collected.write(chunk, 0, read)
            }
        }
        return collected.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    companion object {
        fun create(baseUrl: ApiBaseUrl): HealthRepository =
            HealthRepository(HttpClientFactory.create(), baseUrl)

        private const val MAX_BODY_BYTES = 1024
        private const val READ_CHUNK_BYTES = 256
    }
}
