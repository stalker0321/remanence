package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.security.GeneralSecurityException

/**
 * Versioned account-bound refresh credential. The owner is the canonical
 * local account [UserId]; the token is opaque and never logged.
 */
data class SessionRefreshRecord(
    val ownerUserId: UserId,
    val refreshToken: String,
) {
    override fun toString(): String =
        "SessionRefreshRecord(ownerUserId=${ownerUserId.toRestString()})"
}

/**
 * Secure storage boundary for the sealed rotating refresh credential.
 * The on-disk record is versioned and bound to a canonical owner user id
 * plus the opaque refresh token. Tokens are sealed with the non-exportable
 * Keystore KEK before they touch disk; Room and logs never see them.
 * Plaintext exists only transiently inside this module.
 *
 * Legacy v1 token-only ciphertext, corrupt payloads, and non-canonical
 * owners fail closed. There is no permissive migration.
 */
class SessionTokenStore(
    private val directory: File,
    private val kekBoundary: KekBoundary,
    private val kekAlias: String,
) {

    /** Seals [record] under the KEK and atomically replaces any stored value. */
    fun save(record: SessionRefreshRecord) {
        if (record.refreshToken.isEmpty()) {
            throw GeneralSecurityException("session token must not be empty")
        }
        val aead = loadAead()
        val sealed = aead.encrypt(encode(record), associatedData())
        directory.mkdirs()
        val target = tokenFile()
        val temporary = File(directory, "$FILE_NAME.tmp")
        temporary.writeBytes(sealed)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw GeneralSecurityException("could not persist session record")
        }
    }

    /**
     * Returns the stored bound record or `null` when absent.
     * Fails closed on tampered, legacy, or unreadable storage.
     */
    fun load(): SessionRefreshRecord? {
        val file = tokenFile()
        if (!file.exists()) return null
        val aead = loadAead()
        return decode(aead.decrypt(file.readBytes(), associatedData()))
    }

    /** Removes stored material; idempotent. */
    fun clear() {
        tokenFile().delete()
        File(directory, "$FILE_NAME.tmp").delete()
    }

    private fun tokenFile(): File = File(directory, FILE_NAME)

    private fun loadAead() = kekBoundary.loadKekAead(kekAlias)

    private fun associatedData(): ByteArray =
        AAD_PREFIX.encodeToByteArray() + 0x00 + kekAlias.encodeToByteArray()

    private fun encode(record: SessionRefreshRecord): ByteArray {
        val owner = record.ownerUserId.toRestString().encodeToByteArray()
        val token = record.refreshToken.encodeToByteArray()
        val payload = ByteArray(1 + owner.size + 1 + token.size)
        payload[0] = PAYLOAD_VERSION
        owner.copyInto(payload, 1)
        payload[1 + owner.size] = 0x00
        token.copyInto(payload, 2 + owner.size)
        return payload
    }

    private fun decode(plain: ByteArray): SessionRefreshRecord {
        if (plain.isEmpty() || plain[0] != PAYLOAD_VERSION) {
            throw GeneralSecurityException("unsupported session record")
        }
        val rest = plain.copyOfRange(1, plain.size)
        val separator = rest.indexOf(0)
        if (separator <= 0 || separator >= rest.lastIndex) {
            throw GeneralSecurityException("malformed session record")
        }
        val ownerRaw = rest.copyOfRange(0, separator).decodeToString()
        val token = rest.copyOfRange(separator + 1, rest.size).decodeToString()
        if (token.isEmpty()) {
            throw GeneralSecurityException("session token must not be empty")
        }
        val owner = try {
            UserId.parseRest(ownerRaw)
        } catch (_: IllegalArgumentException) {
            throw GeneralSecurityException("session record owner is not canonical")
        }
        return SessionRefreshRecord(owner, token)
    }

    private companion object {
        const val FILE_NAME = "session.token.sealed"
        const val AAD_PREFIX = "postmark/session/v2"
        const val PAYLOAD_VERSION: Byte = 1
    }
}
