package postmark.core.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DirectoryUserDto(
    @SerialName("user_id") val userId: String,
    val handle: String,
)

@Serializable
internal data class DirectoryKeyBundleDto(
    @SerialName("key_bundle_id") val keyBundleId: String,
    @SerialName("user_id") val userId: String,
    val suite: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("encryption_public_keyset") val encryptionPublicKeyset: String,
    @SerialName("signing_public_keyset") val signingPublicKeyset: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
internal data class DirectoryLookupResponseDto(
    val user: DirectoryUserDto,
    @SerialName("key_bundle") val keyBundle: DirectoryKeyBundleDto,
    @SerialName("directory_version") val directoryVersion: String,
)
