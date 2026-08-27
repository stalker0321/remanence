package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import dev.hryshyn.remanence.core.model.CryptoContextEncoder
import dev.hryshyn.remanence.core.model.SenderRetryPurpose
import dev.hryshyn.remanence.core.model.SenderRetryWrapContextInput
import java.security.GeneralSecurityException

/**
 * M2-P08: sender-owned wrapped retry keyset over the existing
 * non-exportable KEK (KekBoundary). The keyset is serialized only
 * inside the crypto module, encrypted with a fresh 96-bit GCM nonce
 * using the deterministic sender-retry wrap AAD built from a typed
 * [SenderRetryWrapContextInput], and returned as a persistable
 * [WrappedKeysetRecord].
 *
 * Unwrap requires the caller to supply the SAME typed context
 * (owner_user_id, capsule_id, sender_key_bundle_id, purpose) so a
 * wrapped keyset can only be opened for the capsule + sender bundle
 * + purpose it was originally wrapped for. Any mismatch - wrong
 * owner, wrong capsule, wrong sender bundle, wrong purpose, wrong
 * alias, tampered nonce/ciphertext, missing KEK, or malformed
 * record - fails closed inside the AEAD step and never exposes
 * keyset material.
 *
 * The wrapper deliberately does NOT weaken or replace
 * [KeysetKekWrapper]: it sits on the same [KekBoundary] but
 * uses a different AAD so a sender-retry wrap can never be opened
 * by a caller that only has the generic identity-bundle keyset. The
 * on-disk [WrappedKeysetRecord] wire layout and version stay
 * unchanged; the AAD is the only thing that varies between the two
 * wrappers.
 */
class SenderRetryKeysetWrapper(private val kekBoundary: KekBoundary) {

    /**
     * Wraps [keyset] under the [alias] KEK for the given [context].
     * The fresh 96-bit GCM nonce, the wrapped keyset ciphertext, and
     * the alias are persisted in the returned [WrappedKeysetRecord].
     */
    fun wrap(
        alias: String,
        keyset: KeysetHandle,
        context: SenderRetryWrapContextInput,
    ): WrappedKeysetRecord {
        // Typed context already refuses anything outside the accepted
        // purposes at construction, but a redundant guard at the
        // crypto boundary makes the fail-closed intent unambiguous.
        require(context.purpose in SenderRetryPurpose.ACCEPTED) {
            "only ${SenderRetryPurpose.ACCEPTED} are accepted; got ${context.purpose}"
        }
        val aead = kekBoundary.loadKekAead(alias)
        val serialized = TinkProtoKeysetFormat.serializeKeyset(
            keyset,
            InsecureSecretKeyAccess.get(),
        )
        val aad = CryptoContextEncoder.senderRetryWrapAad(context)
        val sealed = aead.encrypt(serialized, aad.toByteArray())
        if (sealed.size <= NONCE_SIZE) {
            throw GeneralSecurityException("sealed sender-retry keyset too short")
        }
        return WrappedKeysetRecord.create(
            alias = alias,
            nonce = sealed.copyOfRange(0, NONCE_SIZE),
            wrappedKeyset = sealed.copyOfRange(NONCE_SIZE, sealed.size),
        )
    }

    /**
     * Unwraps a previously wrapped [record] for the given [context].
     * The supplied context MUST match the context used at wrap time
     * byte-for-byte; any difference changes the AAD and the AEAD
     * step fails closed. A missing KEK, tampered nonce, tampered
     * ciphertext, malformed record, or unknown format version all
     * fail closed before any keyset material is exposed.
     */
    fun unwrap(
        record: WrappedKeysetRecord,
        context: SenderRetryWrapContextInput,
    ): KeysetHandle {
        require(context.purpose in SenderRetryPurpose.ACCEPTED) {
            "only ${SenderRetryPurpose.ACCEPTED} are accepted; got ${context.purpose}"
        }
        if (!kekBoundary.hasKey(record.alias)) {
            throw GeneralSecurityException("no KEK stored for alias ${record.alias}")
        }
        val aead = kekBoundary.loadKekAead(record.alias)
        val sealed = record.nonce + record.wrappedKeyset
        val aad = CryptoContextEncoder.senderRetryWrapAad(context)
        val serialized = try {
            aead.decrypt(sealed, aad.toByteArray())
        } catch (failure: GeneralSecurityException) {
            throw GeneralSecurityException("sender-retry keyset unwrap failed")
        }
        return TinkProtoKeysetFormat.parseKeyset(
            serialized,
            InsecureSecretKeyAccess.get(),
        )
    }

    private companion object {
        internal const val NONCE_SIZE = WrappedKeysetRecord.NONCE_SIZE
    }
}
