package dev.hryshyn.remanence.ui.create

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.CoroutineScope
import dev.hryshyn.remanence.core.data.network.DirectoryLookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import dev.hryshyn.remanence.core.data.network.ResolvedHandleSnapshot
import dev.hryshyn.remanence.core.model.KeyBundleId
import dev.hryshyn.remanence.core.model.NormalizedHandle
import dev.hryshyn.remanence.core.model.UserId

/** Immutable confirmation wiring proof for I04. */
class CreateRecipientFlowTest {

    private val snapshot = ResolvedHandleSnapshot(
        userId = UserId.parseRest("1f0a1234-5678-4abc-9def-aabbccdd1001"),
        handle = NormalizedHandle.parse("mykola"),
        keyBundleId = KeyBundleId.parseRest("2f0a1234-5678-4abc-9def-aabbccdd2002"),
        suite = "HPKE_X25519_HKDF_SHA256_AES256GCM__ED25519",
        protocolVersion = 1,
        encryptionPublicKeysetB64Url = "AAECAw",
        signingPublicKeysetB64Url = "BBECAw",
        directoryVersion = "1",
        keyBundleStatus = "ACTIVE",
    )

    private fun flow() = CreateRecipientFlow(
        picker = RecipientPickerViewModel(
            directory = { _, _ -> DirectoryLookupResult.NotFound },
            accessTokenProvider = { "token" },
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        ),
        store = CreateSessionStore(),
    )

    @Test
    fun resolvedThenConfirmedBindsTheImmutableSnapshot() {
        val flow = flow()

        flow.onResolved(snapshot)
        assertEquals(CreateRecipientFlow.Step.CONFIRM, flow.step.value)

        flow.onConfirm()
        assertEquals(CreateRecipientFlow.Step.BOUND, flow.step.value)
        assertEquals(snapshot.userId, flow.confirmed.value?.userId)
        assertEquals(snapshot.keyBundleId, flow.confirmed.value?.keyBundleId)
    }

    @Test
    fun confirmingTwiceIsRejectedInsteadOfRebinding() {
        val flow = flow()
        flow.onResolved(snapshot)
        flow.onConfirm()

        // A second resolution attempt cannot silently swap the recipient.
        try {
            flow.onResolved(snapshot.copy(keyBundleId = KeyBundleId.parseRest("3f0a1234-5678-4abc-9def-aabbccdd3003")))
            flow.onConfirm()
            throw AssertionError("expected rejection")
        } catch (expected: IllegalStateException) {
            assertEquals("recipient already bound for this session", expected.message)
        }
        // The original binding survives untouched.
        assertEquals("2f0a1234-5678-4abc-9def-aabbccdd2002", flow.confirmed.value?.keyBundleId?.toRestString())
    }

    @Test
    fun restartLookupEndsTheSessionAndClearsPending() {
        val flow = flow()
        flow.onResolved(snapshot)

        flow.restartLookup()

        assertEquals(CreateRecipientFlow.Step.LOOKUP, flow.step.value)
        assertNull(flow.confirmed.value)
    }
}
