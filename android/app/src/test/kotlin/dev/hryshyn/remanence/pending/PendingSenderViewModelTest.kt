package dev.hryshyn.remanence.pending

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.network.PendingBlobResult
import dev.hryshyn.remanence.core.data.network.PendingDraftResult
import dev.hryshyn.remanence.core.data.network.PendingFinalizeResult
import dev.hryshyn.remanence.core.data.network.SendRouteFailure
import dev.hryshyn.remanence.core.data.network.SendRouteResult
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T186 M12: actual sender caller — R1 freeze branches (known-v1
 * handoff with zero escrow vs pending publication to a 5-char code),
 * pin/identity/content fences, owner/lease fencing.
 */
class PendingSenderViewModelTest {

    private val senderId = "01010101-0202-0303-0404-050505050505"
    private val senderBundle = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f"
    private val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun frozenPending() = SendRouteResult.FrozenPending(
        routeId = "0198f0a0-0000-7000-8000-00000000b301",
        deliveryTargetId = target,
        normalizedDestination = "a42@example.test",
        channel = "email",
        claimDeadline = 1814317432L,
        serviceKeyId = "9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b",
        expiresAt = 1798765432L,
    )

    private fun frozenV1() = SendRouteResult.FrozenV1(
        routeId = "0198f0a0-0000-7000-8000-00000000b302",
        userId = "33333333-4444-5555-6666-777777777777",
        keyBundleId = senderBundle,
        bindingRevision = 3L,
        expiresAt = 1798765432L,
    )

    private inner class FakeGate(
        val owner: String,
        var live: Boolean = true,
    ) : PendingRetirementSessionGate {
        override fun capture(): SessionRequestLease =
            SessionRequestLease(UserId.parseRest(owner), 1L)

        override fun accessTokenFor(lease: SessionRequestLease): String? = "token"

        override fun isLive(lease: SessionRequestLease): Boolean =
            live && lease.ownerUserId == UserId.parseRest(owner)
    }

    private fun kotlinx.coroutines.test.TestScope.vm(
        freeze: SendRouteResult = frozenPending(),
        account: ProofAccount? = ProofAccount(senderId, senderBundle),
        gateOwner: String = senderId,
        live: Boolean = true,
        pin: PendingServicePin? = PendingServicePin(
            "9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b",
            "cGlu",
        ),
        identityProvider: (suspend () -> PendingSenderIdentity?)? = null,
        content: PendingSenderContent? = PendingSenderContent(
            listOf("photo-bytes".toByteArray()),
        ),
        contentCalled: (() -> Unit)? = null,
    ): PendingSenderViewModel {
        TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val fallbackIdentity = PendingSenderIdentity(senderId, senderBundle, signing)
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        )
        return PendingSenderViewModel(
            freezePort = PendingSendRouteFlow.SendRoutePort { _, _, _, _, _ -> freeze },
            publishFlowFactory = { materialPort ->
                PendingPublishFlow(
                    draftPort = PendingPublishFlow.DraftPort { _, _, _, _, _, _, _, _, _, _ ->
                        PendingDraftResult.Draft(
                            "0198f0a0-0000-7000-8000-00000000d401",
                            "0f".repeat(32),
                            1798765432L,
                        )
                    },
                    blobPort = PendingPublishFlow.BlobPort { _, blobId, _, _, _, _ ->
                        PendingBlobResult.Uploaded(blobId, 11L)
                    },
                    finalizePort = PendingPublishFlow.FinalizePort { deliveryId, _, _, _, _, _ ->
                        PendingFinalizeResult.Finalized(
                            deliveryId, "AB3DE", 1814317432L, "a•••@•••",
                        )
                    },
                    materialPort = materialPort,
                    sessionGate = FakeGate(gateOwner, live),
                    accountProvider = { account },
                    scope = null,
                )
            },
            contentPort = PendingSenderContentPort {
                contentCalled?.invoke()
                content
            },
            identityPort = PendingSenderIdentityPort { identityProvider?.invoke() ?: fallbackIdentity },
            servicePinProvider = { pin },
            sessionGate = FakeGate(gateOwner, live),
            accountProvider = { account },
            scope = scope,
        )
    }

    @Test
    fun pendingRoutePublishesToCode() = runTest {
        val viewModel = vm()
        viewModel.onKindChange("email")
        viewModel.onDestinationChange("a42@example.test")
        viewModel.send()
        testScheduler.advanceUntilIdle()
        val published = viewModel.state.value as PendingSenderViewModel.State.Published
        assertEquals("AB3DE", published.locatorCode)
        assertEquals("a•••@•••", published.hint)
    }

    @Test
    fun knownV1HandsOffWithZeroEscrow() = runTest {
        var contentTouched = false
        val viewModel = vm(
            freeze = frozenV1(),
            contentCalled = { contentTouched = true },
        )
        viewModel.onDestinationChange("known@example.test")
        viewModel.send()
        testScheduler.advanceUntilIdle()
        val known = viewModel.state.value as PendingSenderViewModel.State.KnownV1
        assertEquals("33333333-4444-5555-6666-777777777777", known.identity.userId)
        // Zero escrow: no pending content was ever built.
        assertTrue(!contentTouched)
    }

    @Test
    fun missingPinBlocksService() = runTest {
        val viewModel = vm(pin = null)
        viewModel.onDestinationChange("a42@example.test")
        viewModel.send()
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingSenderViewModel.State.Failed(PendingSenderViewModel.Message.SERVICE_UNAVAILABLE),
            viewModel.state.value,
        )
    }

    @Test
    fun missingIdentityRequiresRecovery() = runTest {
        val viewModel = vm(identityProvider = { null })
        viewModel.onDestinationChange("a42@example.test")
        viewModel.send()
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingSenderViewModel.State.Failed(PendingSenderViewModel.Message.RECOVERY_REQUIRED),
            viewModel.state.value,
        )
    }

    @Test
    fun switchedOwnerFailsSessionChanged() = runTest {
        val viewModel = vm(
            account = ProofAccount("99999999-2222-3333-4444-555555555555", senderBundle),
            gateOwner = senderId,
        )
        viewModel.onDestinationChange("a42@example.test")
        viewModel.send()
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingSenderViewModel.State.Failed(PendingSenderViewModel.Message.SESSION_CHANGED),
            viewModel.state.value,
        )
    }

    @Test
    fun blankDestinationValidates() = runTest {
        val viewModel = vm()
        viewModel.onDestinationChange("   ")
        viewModel.send()
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingSenderViewModel.State.Failed(PendingSenderViewModel.Message.VALIDATION),
            viewModel.state.value,
        )
    }

    @Test
    fun routeFailureMaps() = runTest {
        val viewModel = vm(
            freeze = SendRouteResult.Failure(SendRouteFailure.ADDRESS_UNAVAILABLE, 400, false),
        )
        viewModel.onDestinationChange("gone@example.test")
        viewModel.send()
        testScheduler.advanceUntilIdle()
        assertEquals(
            PendingSenderViewModel.State.Failed(PendingSenderViewModel.Message.VALIDATION),
            viewModel.state.value,
        )
    }
}
