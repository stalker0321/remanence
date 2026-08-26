package dev.hryshyn.remanence.ui.create

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId

class CreateSessionStoreTest {

    private val userId = UserId.parseRest("1f0a1234-5678-4abc-9def-aabbccdd1001")

    private fun snapshot(bundleId: String = "2f0a1234-5678-4abc-9def-aabbccdd2002") =
        ResolvedHandleSnapshot(
            userId = userId,
            handle = NormalizedHandle.parse("mykola"),
            keyBundleId = KeyBundleId.parseRest(bundleId),
            suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
            protocolVersion = 1,
            encryptionPublicKeysetB64Url = "CIenc",
            signingPublicKeysetB64Url = "CJsig",
            keyBundleStatus = "ACTIVE",
            directoryVersion = "9f1c0d2e",
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.newStore(): CreateSessionStore {
        @Suppress("UNUSED_EXPRESSION")
        testScheduler // keep scheduler reference for future async variants
        return CreateSessionStore()
    }

    @Test
    fun confirmedSnapshotIsReadableWithinSession() {
        val store = CreateSessionStore()
        assertNull(store.confirmedRecipient.value)
        store.confirmRecipient(snapshot())
        assertEquals(snapshot(), store.confirmedRecipient.value)
    }

    @Test
    fun reconfirmationReplacesPreviousBinding() {
        val store = CreateSessionStore()
        store.confirmRecipient(snapshot("2f0a1234-5678-4abc-9def-aabbccdd2002"))
        store.confirmRecipient(snapshot("2f0a1234-5678-4abc-9def-aabbccdd2999"))
        assertEquals(
            KeyBundleId.parseRest("2f0a1234-5678-4abc-9def-aabbccdd2999"),
            store.confirmedRecipient.value!!.keyBundleId,
        )
    }

    @Test
    fun endSessionDropsSnapshotImmediately() {
        val store = CreateSessionStore()
        store.confirmRecipient(snapshot())
        store.endSession()
        assertNull(store.confirmedRecipient.value)
    }

    @Test
    fun newStoreInstanceLikeProcessRestartStartsEmpty() {
        val first = CreateSessionStore()
        first.confirmRecipient(snapshot())
        // A fresh process constructs a fresh store: no snapshot survives.
        val second = CreateSessionStore()
        assertNull(second.confirmedRecipient.value)
        assertTrue(first.confirmedRecipient.value != null)
    }

    @Test
    fun nonActiveBundleCannotBeBound() {
        val store = CreateSessionStore()
        val retired = ResolvedHandleSnapshot(
            userId = userId,
            handle = NormalizedHandle.parse("mykola"),
            keyBundleId = KeyBundleId.parseRest("2f0a1234-5678-4abc-9def-aabbccdd2002"),
            suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
            protocolVersion = 1,
            encryptionPublicKeysetB64Url = "CIenc",
            signingPublicKeysetB64Url = "CJsig",
            keyBundleStatus = "RETIRED",
            directoryVersion = "9f1c0d2e",
        )
        var thrown = false
        try {
            store.confirmRecipient(retired)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
        assertNull(store.confirmedRecipient.value)
    }
}
