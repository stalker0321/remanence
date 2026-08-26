package dev.hryshyn.remanence.core.crypto

import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM contract tests for every [KekBoundary]. The Android Keystore
 * implementation must satisfy the same contract when a device or emulator is
 * available (instrumented roundtrip remains explicitly unverified here).
 */
class KekBoundaryContractTest {

    @Test
    fun freshAliasIsAbsentThenCreatedOnce() {
        val boundary: KekBoundary = InMemoryKekBoundary()
        val alias = "remanence.kek.contract"
        assertFalse(boundary.hasKey(alias))
        boundary.createAes256GcmKey(alias)
        assertTrue(boundary.hasKey(alias))
        assertFailsWith<GeneralSecurityException> { boundary.createAes256GcmKey(alias) }
    }

    @Test
    fun loadedKekAeadRoundTripsAndRejectsWrongAssociatedData() {
        val boundary: KekBoundary = InMemoryKekBoundary()
        boundary.createAes256GcmKey("remanence.kek.contract.roundtrip")
        val aead = boundary.loadKekAead("remanence.kek.contract.roundtrip")
        val plaintext = "wrapped keyset bytes".toByteArray(Charsets.UTF_8)
        val aad = "postmark/kek/v1".toByteArray(Charsets.UTF_8)
        val ciphertext = aead.encrypt(plaintext, aad)
        assertContentEquals(plaintext, aead.decrypt(ciphertext, aad))
        assertFailsWith<GeneralSecurityException> { aead.decrypt(ciphertext, "wrong".toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun loadingMissingAliasFailsClosed() {
        val boundary: KekBoundary = InMemoryKekBoundary()
        assertFailsWith<GeneralSecurityException> { boundary.loadKekAead("remanence.kek.missing") }
    }

    @Test
    fun keySurvivesNewBoundaryInstanceLikeProcessRestart() {
        val first: KekBoundary = InMemoryKekBoundary()
        first.createAes256GcmKey("remanence.kek.restart")
        val ciphertext = first.loadKekAead("remanence.kek.restart").encrypt("payload".toByteArray(), null)

        val second: KekBoundary = InMemoryKekBoundary()
        assertTrue(second.hasKey("remanence.kek.restart"))
        assertContentEquals("payload".toByteArray(), second.loadKekAead("remanence.kek.restart").decrypt(ciphertext, null))
    }
}
