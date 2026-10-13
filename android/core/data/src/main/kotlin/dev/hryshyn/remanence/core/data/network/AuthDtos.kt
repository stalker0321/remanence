package dev.hryshyn.remanence.core.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterKeyBundleDto(
    @SerialName("key_bundle_id") val keyBundleId: String,
    val suite: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("encryption_public_keyset") val encryptionPublicKeyset: String,
    @SerialName("signing_public_keyset") val signingPublicKeyset: String,
)

@Serializable
data class RegisterRequestDto(
    val email: String,
    val password: String,
    val handle: String,
    @SerialName("key_bundle") val keyBundle: RegisterKeyBundleDto,
)

@Serializable
data class RegistrationUserDto(
    @SerialName("user_id") val userId: String,
    val email: String,
    /**
     * T186: nullable per the pending profile contract (T185 §2, capability
     * header v2). v1 servers always send non-null; a null here records a
     * receive-only account that defers the handle until first send. Never
     * replaced by an empty placeholder downstream.
     */
    val handle: String? = null,
    @SerialName("created_at") val createdAt: String,
    /**
     * T186 M7c: capability profile version from staged server 63 wire.
     * Optional typed int (absent in older payloads); the strict shared
     * decoder requires it to be a DECLARED field (unknown-field rejection
     * otherwise breaks new receive/login/recovery decodes).
     */
    @SerialName("profile_version") val profileVersion: Int? = null,
)

/** Wire shape of the active public key bundle metadata returned by auth endpoints. */
@Serializable
data class ActiveKeyBundleMetadataDto(
    @SerialName("key_bundle_id") val keyBundleId: String,
    val suite: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    val status: String,
)

@Serializable
data class RegisterResponseDto(
    val user: RegistrationUserDto,
    @SerialName("active_key_bundle_id") val activeKeyBundleId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_expires_at") val accessExpiresAt: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("refresh_expires_at") val refreshExpiresAt: String,
)

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
)

@Serializable
data class LoginResponseDto(
    val user: RegistrationUserDto,
    @SerialName("active_key_bundle") val activeKeyBundle: ActiveKeyBundleMetadataDto,
    @SerialName("session_id") val sessionId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_expires_at") val accessExpiresAt: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("refresh_expires_at") val refreshExpiresAt: String,
)

@Serializable
data class RefreshRequestDto(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class RefreshResponseDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_expires_at") val accessExpiresAt: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("refresh_expires_at") val refreshExpiresAt: String,
)
