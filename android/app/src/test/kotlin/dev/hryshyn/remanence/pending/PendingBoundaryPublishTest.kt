package dev.hryshyn.remanence.pending

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.crypto.TinkPrimitives
import dev.hryshyn.remanence.core.data.network.PendingBlobResult
import dev.hryshyn.remanence.core.data.network.PendingDraftResult
import dev.hryshyn.remanence.core.data.network.PendingFinalizeResult
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T186 M12d: shared boundary runner — photos-only and full-manifest
 * publication through fake ports, txn fencing, pin/content fences.
 * The Create boundary and the sender sheet drive this same runner.
 */
class PendingBoundaryPublishTest {

    private val senderId = "01010101-0202-0303-0404-050505050505"
    private val senderBundle = "0b0b0b0b-1c1c-2d2d-3e3e-4f4f4f4f4f4f"
    private val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val serviceKey = "9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b"
    private val capsule = "11111111-2222-3333-4444-555555555555"

    private fun frozenPending() = PendingSendRouteFlow.FrozenIdentity.Pending(
        routeId = "0198f0a0-0000-7000-8000-00000000b301",
        deliveryTargetId = target,
        normalizedDestination = "a42@example.test",
        channel = "email",
        claimDeadline = 1814317432L,
        serviceKeyId = serviceKey,
        expiresAt = 1798765432L,
    )

    private inner class SwitchingGate(var owner: String = senderId) : PendingRetirementSessionGate {
        override fun capture(): SessionRequestLease =
            SessionRequestLease(UserId.parseRest(owner), 1L)

        override fun accessTokenFor(lease: SessionRequestLease): String? = "token"

        override fun isLive(lease: SessionRequestLease): Boolean =
            lease.ownerUserId == UserId.parseRest(owner)
    }

    private fun testPin(): PendingServicePin {
        TinkPrimitives.ensureRegistered()
        val signing = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        return PendingServicePin(
            serviceKey,
            java.util.Base64.getEncoder().encodeToString(
                com.google.crypto.tink.TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
                    signing.publicKeysetHandle,
                ),
            ),
        )
    }

    private fun identity(): PendingSenderIdentity {
        TinkPrimitives.ensureRegistered()
        return PendingSenderIdentity(
            senderId, senderBundle,
            KeysetHandle.generateNew(KeyTemplates.get("ED25519")),
        )
    }

    private fun authored(manifests: Boolean = false) = PendingBoundaryPublish.Authored(
        capsuleId = capsule,
        photos = listOf(
            PendingBoundaryPublish.PhotoBytes("photo-bytes".toByteArray(), 1000, 1000),
        ),
        manifests = if (!manifests) {
            null
        } else {
            PendingBoundaryPublish.ManifestInputs(
                senderHandleSnapshot = "mykola",
                frontFingerprintBytes = ByteArray(96) { (it * 3).toByte() },
                noteUtf8 = "hello",
                expression = null,
            )
        },
        claimExpiresAt = 1814317432L,
        createdAtEpochSeconds = 1798765432L,
    )

    private suspend fun run(
        gate: SwitchingGate = SwitchingGate(),
        accountOf: () -> ProofAccount? = { ProofAccount(gate.owner, senderBundle) },
        pin: PendingServicePin? = null,
        authored: PendingBoundaryPublish.Authored = authored(),
        expectedOwnerId: String? = null,
        draftCalls: MutableList<Unit> = mutableListOf(),
    ): PendingBoundaryPublish.Result {
        TinkPrimitives.ensureRegistered()
        return PendingBoundaryPublish.run(
            frozen = frozenPending(),
            authored = authored,
            identity = identity(),
            pin = pin ?: testPin(),
            sessionGate = gate,
            accountProvider = accountOf,
            draftPort = PendingPublishFlow.DraftPort { _, _, _, _, _, _, _, _, _, _ ->
                draftCalls += Unit
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
            expectedOwnerId = expectedOwnerId,
        )
    }

    @Test
    fun photosOnlyPublishesToCode() = runTest {
        val result = run()
        val published = result as PendingBoundaryPublish.Result.Published
        assertEquals("AB3DE", published.locatorCode)
    }

    @Test
    fun fullManifestsPublishKindSet() = runTest {
        val gate = SwitchingGate()
        var kinds: List<Int> = emptyList()
        TinkPrimitives.ensureRegistered()
        val result = PendingBoundaryPublish.run(
            frozen = frozenPending(),
            authored = authored(manifests = true),
            identity = identity(),
            pin = testPin(),
            sessionGate = gate,
            accountProvider = { ProofAccount(senderId, senderBundle) },
            draftPort = PendingPublishFlow.DraftPort { _, _, _, _, _, _, _, artifacts, _, _ ->
                kinds = artifacts.map { it.kind }
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
        )
        assertTrue(result is PendingBoundaryPublish.Result.Published)
        // Recognition + content + photo, server-frozen declaration order.
        assertEquals(listOf(1, 2, 3), kinds)
    }

    @Test
    fun expectedOwnerMismatchFailsWithoutDraft() = runTest {
        val drafts = mutableListOf<Unit>()
        val result = run(
            expectedOwnerId = "99999999-2222-3333-4444-555555555555",
            draftCalls = drafts,
        )
        assertEquals(
            PendingBoundaryPublish.Result.Failed(PendingBoundaryPublish.Reason.SESSION_CHANGED),
            result,
        )
        assertTrue(drafts.isEmpty())
    }

    @Test
    fun malformedPinNeverMints() = runTest {
        val drafts = mutableListOf<Unit>()
        val result = run(
            pin = PendingServicePin(serviceKey, "bm90LWEta2V5c2V0"),
            draftCalls = drafts,
        )
        assertEquals(
            PendingBoundaryPublish.Result.Failed(PendingBoundaryPublish.Reason.SERVICE_UNAVAILABLE),
            result,
        )
        assertTrue(drafts.isEmpty())
    }

    @Test
    fun emptyPhotosBlocked() = runTest {
        val result = run(
            authored = authored().copy(photos = emptyList()),
        )
        assertEquals(
            PendingBoundaryPublish.Result.Failed(PendingBoundaryPublish.Reason.BLOCKED),
            result,
        )
    }
}
