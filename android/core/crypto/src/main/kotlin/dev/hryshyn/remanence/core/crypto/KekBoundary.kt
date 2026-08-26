package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.Aead

/**
 * Creation/load boundary over the non-exportable AES-256-GCM key-encryption
 * key (KEK) that protects serialized private keysets at rest. Implementations
 * must keep the KEK inside the secure store: no method may return key material.
 */
interface KekBoundary {

    fun hasKey(alias: String): Boolean

    /**
     * Generates a fresh non-exportable AES-256-GCM KEK under [alias].
     * Refuses to replace an existing key so wrapped material can never be
     * silently orphaned.
     */
    fun createAes256GcmKey(alias: String)

    /**
     * Loads an AEAD bound to the stored KEK for [alias].
     * Fails closed when no key exists for the alias.
     */
    fun loadKekAead(alias: String): Aead
}
