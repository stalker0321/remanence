package dev.hryshyn.remanence.core.data.network

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.UserId

/** Public portion of a historical (ACTIVE/RETIRED/REVOKED) key bundle. */
data class HistoricalKeyBundle(
    val keyBundleId: KeyBundleId,
    val ownerUserId: UserId,
    val suite: String,
    val protocolVersion: Int,
    val encryptionPublicKeysetB64Url: String,
    val signingPublicKeysetB64Url: String,
    val status: String,
)

enum class KeyBundleFailure {
    NOT_FOUND,
    NETWORK,
    HTTP,
    INVALID_RESPONSE,
}

sealed interface KeyBundleByIdResult {
    data class Found(val bundle: HistoricalKeyBundle) : KeyBundleByIdResult

    data object NotFound : KeyBundleByIdResult

    data class Failure(
        val reason: KeyBundleFailure,
        val httpStatus: Int? = null,
    ) : KeyBundleByIdResult
}

/**
 * Fetches the immutable public portion of any stored bundle by ID so
 * recipients can verify capsules signed before a sender rotated keys
 * (protocol.md section 6). Never resolves routing by handle and never
 * returns private material or email.
 */
class KeyBundleByIdRepository internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {

    suspend fun fetch(keyBundleId: String, accessToken: String): KeyBundleByIdResult {
        val request = Request.Builder()
            .url(baseUrl.resolve("v1/directory/key-bundles/$keyBundleId"))
            .header("Accept", "application/json")
            .header(AUTHORIZATION_HEADER, BEARER_PREFIX + accessToken)
            .get()
            .build()
        return try {
            client.newCall(request).executeAsync().use { response ->
                interpret(response)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            KeyBundleByIdResult.Failure(KeyBundleFailure.NETWORK)
        }
    }

    private fun interpret(response: okhttp3.Response): KeyBundleByIdResult {
        if (response.code == 404) return KeyBundleByIdResult.NotFound
        if (!response.isSuccessful) {
            return KeyBundleByIdResult.Failure(KeyBundleFailure.HTTP, response.code)
        }
        val contentType = response.body.contentType()
        if (contentType == null || contentType.type != "application" || contentType.subtype != "json") {
            return KeyBundleByIdResult.Failure(KeyBundleFailure.INVALID_RESPONSE)
        }
        val dto = try {
            NetworkJson.decodeFromString<DirectoryKeyBundleDto>(response.body.string())
        } catch (_: SerializationException) {
            return KeyBundleByIdResult.Failure(KeyBundleFailure.INVALID_RESPONSE)
        }
        if (dto.status !in KNOWN_STATUSES) {
            return KeyBundleByIdResult.Failure(KeyBundleFailure.INVALID_RESPONSE)
        }
        val bundle = try {
            HistoricalKeyBundle(
                keyBundleId = KeyBundleId.parseRest(dto.keyBundleId),
                ownerUserId = UserId.parseRest(dto.userId),
                suite = dto.suite,
                protocolVersion = dto.protocolVersion,
                encryptionPublicKeysetB64Url = dto.encryptionPublicKeyset,
                signingPublicKeysetB64Url = dto.signingPublicKeyset,
                status = dto.status,
            )
        } catch (_: IllegalArgumentException) {
            return KeyBundleByIdResult.Failure(KeyBundleFailure.INVALID_RESPONSE)
        }
        return KeyBundleByIdResult.Found(bundle)
    }

    companion object {
        fun create(baseUrl: ApiBaseUrl): KeyBundleByIdRepository =
            KeyBundleByIdRepository(HttpClientFactory.create(), baseUrl)

        internal val KNOWN_STATUSES = setOf("ACTIVE", "RETIRED", "REVOKED")
        const val AUTHORIZATION_HEADER: String = "Authorization"
        const val BEARER_PREFIX: String = RefreshingAuthenticator.BEARER_PREFIX
    }
}
