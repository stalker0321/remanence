package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId
import okhttp3.coroutines.executeAsync

/**
 * Immutable snapshot bound at recipient-confirmation time. Carries the exact
 * public keyset bytes shown to the user; never contains an email address.
 */
data class ResolvedHandleSnapshot(
    val userId: UserId,
    val handle: NormalizedHandle,
    val keyBundleId: KeyBundleId,
    val suite: String,
    val protocolVersion: Int,
    val encryptionPublicKeysetB64Url: String,
    val signingPublicKeysetB64Url: String,
    val keyBundleStatus: String,
    val directoryVersion: String,
)

enum class DirectoryFailure {
    NOT_FOUND,
    NETWORK,
    HTTP,
    INVALID_RESPONSE,
}

sealed interface DirectoryLookupResult {
    data class Found(val snapshot: ResolvedHandleSnapshot) : DirectoryLookupResult

    data object NotFound : DirectoryLookupResult

    data class Failure(
        val reason: DirectoryFailure,
        val httpStatus: Int? = null,
    ) : DirectoryLookupResult
}

class DirectoryRepository internal constructor(
    private val client: okhttp3.OkHttpClient,
    private val baseUrl: ApiBaseUrl,
) {
    /** Looks up [rawHandle]; the wire handle is percent-encoded and never cached here. */
    suspend fun lookup(rawHandle: String): DirectoryLookupResult {
        val encodedPath = "v1/directory/handles/" + java.net.URLEncoder.encode(rawHandle, "UTF-8")
            .replace("+", "%20")
        val request = okhttp3.Request.Builder()
            .url(baseUrl.resolve(encodedPath))
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            client.newCall(request).executeAsync().use { response ->
                interpret(response)
            }
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (_: java.io.IOException) {
            DirectoryLookupResult.Failure(DirectoryFailure.NETWORK)
        }
    }

    private fun interpret(response: okhttp3.Response): DirectoryLookupResult {
        if (response.code == 404) return DirectoryLookupResult.NotFound
        if (!response.isSuccessful) {
            return DirectoryLookupResult.Failure(DirectoryFailure.HTTP, response.code)
        }
        val contentType = response.body.contentType()
        if (contentType == null || contentType.type != "application" || contentType.subtype != "json") {
            return DirectoryLookupResult.Failure(DirectoryFailure.INVALID_RESPONSE)
        }
        val dto = try {
            NetworkJson.decodeFromString<DirectoryLookupResponseDto>(response.body.string())
        } catch (_: kotlinx.serialization.SerializationException) {
            return DirectoryLookupResult.Failure(DirectoryFailure.INVALID_RESPONSE)
        }
        val snapshot = try {
            mapToSnapshot(dto)
        } catch (_: IllegalArgumentException) {
            return DirectoryLookupResult.Failure(DirectoryFailure.INVALID_RESPONSE)
        }
        if (snapshot.handle.value != dto.user.handle ||
            snapshot.userId.toRestString() != dto.user.userId ||
            runCatching { UserId.parseRest(dto.keyBundle.userId) }.getOrNull() != snapshot.userId
        ) {
            // Summary block and key-bundle block must agree on the routed identity.
            return DirectoryLookupResult.Failure(DirectoryFailure.INVALID_RESPONSE)
        }
        return DirectoryLookupResult.Found(snapshot)
    }

    internal fun mapToSnapshot(dto: DirectoryLookupResponseDto): ResolvedHandleSnapshot =
        mapDirectoryLookupToSnapshot(dto)

}

/** Shared structural mapping for the two public-shape directory lookups. */
internal fun mapDirectoryLookupToSnapshot(dto: DirectoryLookupResponseDto): ResolvedHandleSnapshot =
    ResolvedHandleSnapshot(
        userId = UserId.parseRest(dto.user.userId),
        handle = NormalizedHandle.parse(dto.user.handle),
        keyBundleId = KeyBundleId.parseRest(dto.keyBundle.keyBundleId),
        suite = dto.keyBundle.suite,
        protocolVersion = dto.keyBundle.protocolVersion,
        encryptionPublicKeysetB64Url = dto.keyBundle.encryptionPublicKeyset,
        signingPublicKeysetB64Url = dto.keyBundle.signingPublicKeyset,
        keyBundleStatus = dto.keyBundle.status,
        directoryVersion = dto.directoryVersion,
    )
