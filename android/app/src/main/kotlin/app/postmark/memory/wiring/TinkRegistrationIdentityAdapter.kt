package app.postmark.memory.wiring

import postmark.core.crypto.IdentityBundleRepository
import postmark.core.crypto.KekBoundary

/** Stable, client-generated identity snapshot used for one registration flow. */
data class PreparedIdentity(
    val keyBundleId: String,
    val encryptionPublicKeysetB64Url: String,
    val signingPublicKeysetB64Url: String,
) {
    companion object {
        const val SUITE: String = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519"
        const val PROTOCOL_VERSION: Int = 1
    }
}

/**
 * Binds [RegistrationIdentityPort][app.postmark.memory.auth.RegistrationIdentityPort]
 * to the real Tink/Keystore identity bundle. The key bundle ID is derived
 * deterministically from the encryption public keyset so retries reuse the
 * same client-generated UUID without extra storage.
 */
class TinkRegistrationIdentityAdapter(
    private val bundleRepository: IdentityBundleRepository,
    private val kekBoundary: KekBoundary,
    private val kekAlias: String,
) : app.postmark.memory.auth.RegistrationIdentityPort {

    override fun prepareIdentity(): PreparedIdentity {
        if (!kekBoundary.hasKey(kekAlias)) {
            kekBoundary.createAes256GcmKey(kekAlias)
        }
        val exports = when (val loaded = bundleRepository.loadPublicExports()) {
            is IdentityBundleRepository.PublicExportsResult.Available -> loaded
            IdentityBundleRepository.PublicExportsResult.RecoveryRequired -> try {
                val created = bundleRepository.createFresh(kekAlias)
                IdentityBundleRepository.PublicExportsResult.Available(
                    created.encryptionPublicKeyset,
                    created.signingPublicKeyset,
                )
            } catch (_: Exception) {
                throw app.postmark.memory.auth.IdentityRecoveryRequiredException()
            }
        }
        return PreparedIdentity(
            keyBundleId = deriveKeyBundleId(exports.encryptionPublicKeyset),
            encryptionPublicKeysetB64Url = encodeBase64Url(exports.encryptionPublicKeyset),
            signingPublicKeysetB64Url = encodeBase64Url(exports.signingPublicKeyset),
        )
    }

    /** Deterministic client-generated UUID bound to this exact identity. */
    private fun deriveKeyBundleId(encryptionPublicKeyset: ByteArray): String =
        java.util.UUID.nameUUIDFromBytes(encryptionPublicKeyset).toString()

    private fun encodeBase64Url(value: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value)
}
