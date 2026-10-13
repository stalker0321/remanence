package dev.hryshyn.remanence.pending

import dev.hryshyn.remanence.core.data.network.PendingBlobResult
import dev.hryshyn.remanence.core.data.network.PendingDraftResult
import dev.hryshyn.remanence.core.data.network.PendingFinalizeResult
import dev.hryshyn.remanence.core.data.network.PendingPublishFailure
import dev.hryshyn.remanence.core.data.network.SessionRequestLease
import dev.hryshyn.remanence.core.model.UserId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T186 M11: v2 sender publication flow (frozen R1 → R3 → R4 → R5).
 * Fake ports, no network: draft → blob uploads → finalize → handwritten
 * code result; owner/lease fences; conflict mapping. The receiver
 * journey is NOT claimed here.
 */
class PendingPublishFlowTest {

    private val senderId = "01010101-0202-0303-0404-050505050505"
    private val capsule = "11111111-2222-3333-4444-555555555555"
    private val target = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun frozenPending() = PendingSendRouteFlow.FrozenIdentity.Pending(
        routeId = "0198f0a0-0000-7000-8000-00000000b301",
        deliveryTargetId = target,
        normalizedDestination = "a42@example.test",
        channel = "email",
        claimDeadline = 1814317432L,
        serviceKeyId = "9f9f9f9f-8e8e-7d7d-6c6c-5b5b5b5b5b5b",
        expiresAt = 1798765432L,
    )

    private fun gate(owner: String = senderId, live: Boolean = true) =
        object : PendingRetirementSessionGate {
            override fun capture(): SessionRequestLease =
                SessionRequestLease(UserId.parseRest(owner), 1L)

            override fun accessTokenFor(lease: SessionRequestLease): String? = "token"

            override fun isLive(lease: SessionRequestLease): Boolean = live
        }

    private fun material() = PendingPublishFlow.PublishMaterial(
        salt32 = ByteArray(32),
        claimExpiresAt = 1814317432L,
        artifacts = listOf(
            PendingPublishFlow.ArtifactMaterial(
                blobId = "cccccccc-dddd-eeee-ffff-000000000001",
                kind = 3,
                ordinal = 0,
                ciphertext = "sealed-bytes".toByteArray(),
            ),
        ),
        statementB64 = "Uw==",
        statementSignature69B64 = "Ux==",
        escrowPayloadB64 = "RXNj",
    )

    private fun flow(
        gate: PendingRetirementSessionGate = gate(),
        account: ProofAccount? = ProofAccount(senderId, "bundle"),
        draft: PendingPublishFlow.DraftPort = PendingPublishFlow.DraftPort { _, _, _, _, _, _, _, _, _, _ ->
            PendingDraftResult.Draft(
                "0198f0a0-0000-7000-8000-00000000d401",
                "0f".repeat(32),
                1798765432L,
            )
        },
        blob: PendingPublishFlow.BlobPort = PendingPublishFlow.BlobPort { _, _, _, _, _, _ ->
            PendingBlobResult.Uploaded("cccccccc-dddd-eeee-ffff-000000000001", 12L)
        },
        finalize: PendingPublishFlow.FinalizePort = PendingPublishFlow.FinalizePort { _, _, _, _, _, _ ->
            PendingFinalizeResult.Finalized(
                "0198f0a0-0000-7000-8000-00000000d401",
                "AB3DE",
                1814317432L,
                "a•••@•••",
            )
        },
    ): PendingPublishFlow = PendingPublishFlow(
        draftPort = draft,
        blobPort = blob,
        finalizePort = finalize,
        materialPort = PendingPublishFlow.MaterialPort { material() },
        sessionGate = gate,
        accountProvider = { account },
        scope = null,
    )

    @Test
    fun frozenRoutePublishesToHandwrittenCode() = runTest {
        val result = flow().publish(
            frozenPending(), capsule,
            "b2c3d4e5-f6a7-48b9-c0d1-e2f3a4b5c6d7",
            "c3d4e5f6-a7b8-49c0-d1e2-f3a4b5c6d7e8",
        )
        assertTrue(result is PendingPublishFlow.State.Published)
        val published = result as PendingPublishFlow.State.Published
        assertEquals("AB3DE", published.locatorCode)
        assertEquals("a•••@•••", published.hint)
    }

    @Test
    fun routeStaleBlocksWithoutCode() = runTest {
        val result = flow(
            draft = PendingPublishFlow.DraftPort { _, _, _, _, _, _, _, _, _, _ ->
                PendingDraftResult.Failure(PendingPublishFailure.ROUTE_STALE, 409, false)
            },
        ).publish(frozenPending(), capsule, "k1", "k2")
        assertEquals(
            PendingPublishFlow.State.Failed(PendingPublishFlow.Reason.BLOCKED),
            result,
        )
    }

    @Test
    fun finalizeConflictMaps() = runTest {
        val result = flow(
            finalize = PendingPublishFlow.FinalizePort { _, _, _, _, _, _ ->
                PendingFinalizeResult.Failure(PendingPublishFailure.IDEMPOTENCY_CONFLICT, 409, false)
            },
        ).publish(frozenPending(), capsule, "k1", "k2")
        assertEquals(
            PendingPublishFlow.State.Failed(PendingPublishFlow.Reason.CONFLICT),
            result,
        )
    }

    @Test
    fun switchedOwnerFailsSessionChanged() = runTest {
        val result = flow(
            gate = gate(owner = "99999999-2222-3333-4444-555555555555"),
            account = ProofAccount(senderId, "bundle"),
        ).publish(frozenPending(), capsule, "k1", "k2")
        assertEquals(
            PendingPublishFlow.State.Failed(PendingPublishFlow.Reason.SESSION_CHANGED),
            result,
        )
    }

    @Test
    fun deadLeaseFailsSessionChanged() = runTest {
        val result = flow(gate = gate(live = false))
            .publish(frozenPending(), capsule, "k1", "k2")
        assertEquals(
            PendingPublishFlow.State.Failed(PendingPublishFlow.Reason.SESSION_CHANGED),
            result,
        )
    }

    @Test
    fun missingMaterialBlocks() = runTest {
        val blocked = PendingPublishFlow(
            draftPort = PendingPublishFlow.DraftPort { _, _, _, _, _, _, _, _, _, _ ->
                PendingDraftResult.Draft("d", "0f".repeat(32), 1L)
            },
            blobPort = PendingPublishFlow.BlobPort { _, _, _, _, _, _ ->
                PendingBlobResult.Uploaded("b", 1L)
            },
            finalizePort = PendingPublishFlow.FinalizePort { _, _, _, _, _, _ ->
                PendingFinalizeResult.Finalized("d", "AB3DE", 1L, "h")
            },
            materialPort = PendingPublishFlow.MaterialPort { null },
            sessionGate = gate(),
            accountProvider = { ProofAccount(senderId, "bundle") },
            scope = null,
        )
        val result = blocked.publish(frozenPending(), capsule, "k1", "k2")
        val failed = result as PendingPublishFlow.State.Failed
        assertEquals(PendingPublishFlow.Reason.BLOCKED, failed.reason)
    }
}
