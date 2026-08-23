package postmark.core.crypto

import com.google.crypto.tink.KeysetHandle
import java.io.File
import java.security.GeneralSecurityException

/**
 * Owns the locally persisted account identity bundle: one HPKE encryption
 * keyset plus one independent Ed25519 signing keyset, each stored only as a
 * [WrappedKeysetRecord] sealed by the device KEK.
 *
 * Guarantees:
 * - creation refuses to silently replace any existing identity material;
 * - loading never fabricates a replacement bundle: anything missing,
 *   incomplete, or not unwrappable is reported as [LoadResult.RecoveryRequired]
 *   so the caller can surface an explicit recovery decision instead.
 */
class IdentityBundleRepository(
    private val baseDirectory: File,
    private val wrapper: KeysetKekWrapper,
) {

    sealed interface LoadResult {
        data class Available(
            val encryptionHandle: KeysetHandle,
            val signingHandle: KeysetHandle,
        ) : LoadResult

        data object RecoveryRequired : LoadResult
    }

    /**
     * Generates a fresh identity bundle and persists it wrapped under the
     * given KEK alias. Throws instead of overwriting any existing record.
     */
    fun createFresh(kekAlias: String): AccountIdentityGenerator.AccountIdentity {
        if (encryptionFile().exists() || signingFile().exists()) {
            throw GeneralSecurityException("identity bundle already exists; refusing silent replacement")
        }
        val identity = AccountIdentityGenerator().generate()
        writeAtomically(encryptionFile(), wrapper.wrap(kekAlias, identity.encryptionPrivateHandle))
        try {
            writeAtomically(signingFile(), wrapper.wrap(kekAlias, identity.signingPrivateHandle))
        } catch (firstWriteFailed: Exception) {
            encryptionFile().delete()
            throw firstWriteFailed
        }
        return identity
    }

    fun load(): LoadResult {
        val encryptionRecord = readRecord(encryptionFile()) ?: return LoadResult.RecoveryRequired
        val signingRecord = readRecord(signingFile()) ?: return LoadResult.RecoveryRequired
        val encryption = try {
            wrapper.unwrap(encryptionRecord)
        } catch (unusable: Exception) {
            return LoadResult.RecoveryRequired
        }
        val signing = try {
            wrapper.unwrap(signingRecord)
        } catch (unusable: Exception) {
            return LoadResult.RecoveryRequired
        }
        return LoadResult.Available(encryption, signing)
    }

    fun exists(): Boolean = encryptionFile().exists() || signingFile().exists()

    /**
     * Serialized public-only keysets of the stored bundle. Lets callers
     * register or display identity without touching private handles.
     */
    sealed interface PublicExportsResult {
        data class Available(
            val encryptionPublicKeyset: ByteArray,
            val signingPublicKeyset: ByteArray,
        ) : PublicExportsResult

        data object RecoveryRequired : PublicExportsResult
    }

    fun loadPublicExports(): PublicExportsResult = when (val result = load()) {
        is LoadResult.Available -> PublicExportsResult.Available(
            encryptionPublicKeyset = serializeWithoutSecret(result.encryptionHandle),
            signingPublicKeyset = serializeWithoutSecret(result.signingHandle),
        )
        LoadResult.RecoveryRequired -> PublicExportsResult.RecoveryRequired
    }

    internal fun serializeWithoutSecret(handle: KeysetHandle): ByteArray =
        com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeysetWithoutSecret(handle.publicKeysetHandle)

    private fun encryptionFile(): File = File(baseDirectory, ENCRYPTION_FILE_NAME)

    private fun signingFile(): File = File(baseDirectory, SIGNING_FILE_NAME)

    private fun readRecord(file: File): WrappedKeysetRecord? {
        if (!file.exists()) return null
        return WrappedKeysetRecord.parse(file.readBytes())
    }

    private fun writeAtomically(target: File, record: WrappedKeysetRecord) {
        baseDirectory.mkdirs()
        val temporary = File(baseDirectory, "${target.name}.tmp")
        temporary.writeBytes(record.serialize())
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw GeneralSecurityException("could not persist ${target.name}")
        }
    }

    private companion object {
        const val ENCRYPTION_FILE_NAME = "identity.encryption.pwks"
        const val SIGNING_FILE_NAME = "identity.signing.pwks"
    }
}
