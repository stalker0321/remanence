package postmark.core.crypto

import java.io.File
import java.security.GeneralSecurityException

/**
 * Secure storage boundary for opaque authentication session material
 * (the rotating refresh token). Tokens are sealed with the non-exportable
 * Keystore KEK before they touch disk; Room and logs never see them.
 * Plaintext exists only transiently inside this module.
 */
class SessionTokenStore(
    private val directory: File,
    private val kekBoundary: KekBoundary,
    private val kekAlias: String,
) {

    /** Seals [token] under the KEK and atomically replaces any stored value. */
    fun save(token: String) {
        if (token.isEmpty()) {
            throw GeneralSecurityException("session token must not be empty")
        }
        val aead = loadAead()
        val sealed = aead.encrypt(token.encodeToByteArray(), associatedData())
        directory.mkdirs()
        val target = tokenFile()
        val temporary = File(directory, "$FILE_NAME.tmp")
        temporary.writeBytes(sealed)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw GeneralSecurityException("could not persist session token")
        }
    }

    /**
     * Returns the stored token or `null` when absent.
     * Fails closed on tampered or unreadable storage.
     */
    fun load(): String? {
        val file = tokenFile()
        if (!file.exists()) return null
        val aead = loadAead()
        return aead.decrypt(file.readBytes(), associatedData()).decodeToString()
    }

    /** Removes stored material; idempotent. */
    fun clear() {
        tokenFile().delete()
        File(directory, "$FILE_NAME.tmp").delete()
    }

    private fun tokenFile(): File = File(directory, FILE_NAME)

    private fun loadAead() = kekBoundary.loadKekAead(kekAlias)

    private fun associatedData(): ByteArray =
        "postmark/session/v1".encodeToByteArray() + 0x00 + kekAlias.encodeToByteArray()

    private companion object {
        const val FILE_NAME = "session.token.sealed"
    }
}
