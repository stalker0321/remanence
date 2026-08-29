package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.data.fingerprints.SecretSealer
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.index.DurableSenderIndexBundle
import dev.hryshyn.remanence.index.SenderIndexBundleStageFailure
import dev.hryshyn.remanence.index.SenderIndexBundleStageRequest
import dev.hryshyn.remanence.index.SenderIndexBundleStageResult
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hryshyn.remanence.protocol.v1.PublishStatement

class SenderIndexBundlePersistenceAdapterTest {

    private val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a201")
    private val otherOwner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000a202")
    private val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000a211")
    private val recognition = RecognitionManifestContent(
        protocolVersion = 1,
        capsuleIdRaw = capsule.toProtoBytes().toByteArray(),
        senderHandleSnapshot = "sender_1",
        createdAtEpochSeconds = 1_700_000_000L,
        placeLabel = "private place",
        frontFingerprint = byteArrayOf(1, 2, 3),
        backFingerprint = byteArrayOf(4, 5, 6),
    )
    private val verified = IncomingVerifiedControlIndexPayload(
        statement = PublishStatement.getDefaultInstance(),
        recognition = recognition,
    )
    private val request = IncomingVerifiedControlIndexPersistenceRequest(
        ownerUserId = owner,
        capsuleId = capsule,
        verified = verified,
    )

    @Test
    fun freshAndReplayedStagesBothBecomeDurableAndBindOneExactRequest() = runBlocking {
        val calls = mutableListOf<SenderIndexBundleStageRequest>()
        val adapter = SenderIndexBundlePersistenceAdapter { stageRequest ->
            calls += stageRequest
            SenderIndexBundleStageResult.Staged(
                durable = durableCapability(),
                replayed = calls.size == 2,
            )
        }

        assertSame(
            IncomingVerifiedControlIndexPersistenceResult.Durable,
            adapter.persist(request, owner),
        )
        assertSame(
            IncomingVerifiedControlIndexPersistenceResult.Durable,
            adapter.persist(request, owner),
        )

        assertEquals(2, calls.size)
        calls.forEach { stageRequest ->
            assertEquals(owner, stageRequest.authenticatedOwnerUserId)
            assertEquals(owner, stageRequest.ownerUserId)
            assertEquals(capsule, stageRequest.capsuleId)
            assertSame(recognition, stageRequest.verifiedRecognition)
        }
    }

    @Test
    fun nullAndMismatchedOwnerStageGuardsAreTerminalOwnerRejections() = runBlocking {
        val noOwner = SenderIndexBundlePersistenceAdapter {
            SenderIndexBundleStageResult.Failure(
                SenderIndexBundleStageFailure.NO_AUTHENTICATED_OWNER,
                retryable = false,
            )
        }.persist(request, owner)
        val mismatch = SenderIndexBundlePersistenceAdapter {
            SenderIndexBundleStageResult.Failure(
                SenderIndexBundleStageFailure.OWNER_MISMATCH,
                retryable = false,
            )
        }.persist(request, otherOwner)

        assertEquals(
            IncomingVerifiedControlIndexPersistenceResult.Rejected(
                IncomingVerifiedControlIndexPersistenceRejectionReason.OWNER_MISMATCH,
            ),
            noOwner,
        )
        assertEquals(
            IncomingVerifiedControlIndexPersistenceResult.Rejected(
                IncomingVerifiedControlIndexPersistenceRejectionReason.OWNER_MISMATCH,
            ),
            mismatch,
        )
    }

    @Test
    fun producibleTerminalStageFailuresKeepTheirExactClassification() = runBlocking {
        val cases = listOf(
            SenderIndexBundleStageFailure.NO_AUTHENTICATED_OWNER to
                IncomingVerifiedControlIndexPersistenceRejectionReason.OWNER_MISMATCH,
            SenderIndexBundleStageFailure.OWNER_MISMATCH to
                IncomingVerifiedControlIndexPersistenceRejectionReason.OWNER_MISMATCH,
            SenderIndexBundleStageFailure.INVALID_VERIFIED_RECOGNITION to
                IncomingVerifiedControlIndexPersistenceRejectionReason.INVALID_VERIFIED_PAYLOAD,
            SenderIndexBundleStageFailure.PATH_UNSAFE to
                IncomingVerifiedControlIndexPersistenceRejectionReason.LOCAL_CAPABILITY_UNAVAILABLE,
            SenderIndexBundleStageFailure.DESTINATION_CONFLICT to
                IncomingVerifiedControlIndexPersistenceRejectionReason.LOCAL_CAPABILITY_UNAVAILABLE,
            SenderIndexBundleStageFailure.SEALING_FAILED to
                IncomingVerifiedControlIndexPersistenceRejectionReason.LOCAL_CAPABILITY_UNAVAILABLE,
            SenderIndexBundleStageFailure.ATOMIC_MOVE_UNAVAILABLE to
                IncomingVerifiedControlIndexPersistenceRejectionReason.LOCAL_CAPABILITY_UNAVAILABLE,
            SenderIndexBundleStageFailure.DURABILITY_UNAVAILABLE to
                IncomingVerifiedControlIndexPersistenceRejectionReason.LOCAL_CAPABILITY_UNAVAILABLE,
        )
        cases.forEach { (reason, expected) ->
            val result = SenderIndexBundlePersistenceAdapter {
                SenderIndexBundleStageResult.Failure(reason, retryable = false)
            }.persist(request, owner)

            assertEquals(
                "reason=$reason",
                IncomingVerifiedControlIndexPersistenceResult.Rejected(expected),
                result,
            )
        }
    }

    @Test
    fun producibleRetryableStageFailuresKeepDependencyAndStorageDistinct() = runBlocking {
        val cases = listOf(
            SenderIndexBundleStageFailure.SEALING_FAILED to
                IncomingVerifiedControlIndexPersistenceRetryReason.DEPENDENCY_UNAVAILABLE,
            SenderIndexBundleStageFailure.LOCAL_STORAGE to
                IncomingVerifiedControlIndexPersistenceRetryReason.LOCAL_STORAGE,
        )
        cases.forEach { (reason, expected) ->
            val result = SenderIndexBundlePersistenceAdapter {
                SenderIndexBundleStageResult.Failure(reason, retryable = true)
            }.persist(request, owner)

            assertEquals(
                "reason=$reason",
                IncomingVerifiedControlIndexPersistenceResult.Retryable(expected),
                result,
            )
        }
    }

    @Test
    fun thrownProviderFailureIsRedactedAndRetryable() = runBlocking {
        val result = SenderIndexBundlePersistenceAdapter {
            throw IOException("private path and provider detail")
        }.persist(request, owner)

        assertEquals(
            IncomingVerifiedControlIndexPersistenceResult.Retryable(
                IncomingVerifiedControlIndexPersistenceRetryReason.DEPENDENCY_UNAVAILABLE,
            ),
            result,
        )
        assertFalse(result.toString().contains("private"))
        assertFalse(result.toString().contains("provider"))
    }

    @Test
    fun realStagerInvalidRecognitionIsInvalidPayloadRatherThanLocalCapability() = runBlocking {
        val filesDir = File(
            System.getProperty("java.io.tmpdir"),
            "remanence-a12b-adapter-${System.nanoTime()}",
        )
        check(filesDir.mkdirs())
        try {
            val neverUsedSealer = object : SecretSealer {
                override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray =
                    throw AssertionError("invalid recognition must fail before sealing")

                override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray =
                    throw AssertionError("invalid recognition must fail before unsealing")
            }
            val result = SenderIndexBundlePersistenceAdapter(
                dev.hryshyn.remanence.index.SenderIndexBundleStager(
                    AccountScopedFileRoots(filesDir),
                    neverUsedSealer,
                ),
            ).persist(request, owner)

            assertEquals(
                IncomingVerifiedControlIndexPersistenceResult.Rejected(
                    IncomingVerifiedControlIndexPersistenceRejectionReason.INVALID_VERIFIED_PAYLOAD,
                ),
                result,
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun cancellationPropagatesUnchanged() = runBlocking {
        val cancellation = CancellationException("private cancellation")
        var thrown: Throwable? = null
        try {
            SenderIndexBundlePersistenceAdapter {
                throw cancellation
            }.persist(request, owner)
        } catch (failure: Throwable) {
            thrown = failure
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun requestStageResultAndCapabilitiesAreRedacted() {
        val privatePath = "/private/index-bundle/path"
        val staged = SenderIndexBundleStageResult.Staged(
            durable = DurableSenderIndexBundle(
                ownerUserId = owner,
                capsuleId = capsule,
                destinationFile = File(privatePath),
                ciphertextSha256 = ByteArray(32) { 9 },
                ciphertextSizeBytes = 12,
            ),
            replayed = false,
        )

        assertFalse(request.toString().contains("sender_1"))
        assertFalse(request.toString().contains("private place"))
        assertFalse(staged.toString().contains(privatePath))
        assertFalse(staged.durable.toString().contains(privatePath))
        assertTrue(staged.toString().contains("redacted"))
    }

    private fun durableCapability() = DurableSenderIndexBundle(
        ownerUserId = owner,
        capsuleId = capsule,
        destinationFile = File("/private/not-exposed"),
        ciphertextSha256 = ByteArray(32) { 7 },
        ciphertextSizeBytes = 7,
    )
}
