package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.data.fingerprints.SecretSealer
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.TestSenderVerification
import dev.hryshyn.remanence.index.DurableSenderIndexBundle
import dev.hryshyn.remanence.index.SenderIndexBundleStageFailure
import dev.hryshyn.remanence.index.SenderIndexBundleStageRequest
import dev.hryshyn.remanence.index.SenderIndexBundleStageResult
import dev.hryshyn.remanence.core.recognition.ExtractionQuality
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintKeypoint
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.PostcardFingerprint
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
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
        protocolVersion = RecognitionManifestCodec.FORMAT_VERSION,
        capsuleIdRaw = capsule.toProtoBytes().toByteArray(),
        senderHandleSnapshot = "sender_1",
        createdAtEpochSeconds = 1_700_000_000L,
        placeLabel = "private place",
        frontFingerprint = byteArrayOf(1, 2, 3),
    )
    private val verified = IncomingVerifiedControlIndexPayload(
        statement = PublishStatement.getDefaultInstance(),
        recognition = recognition,
        senderVerification = TestSenderVerification.forCapsule(capsule),
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
            SenderIndexBundleStageFailure.DEPENDENCY_UNAVAILABLE to
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
    fun realStagerReplayUnsealFailureIsDependencyUnavailable() = runBlocking {
        val filesDir = File(
            System.getProperty("java.io.tmpdir"),
            "remanence-a12b-replay-${System.nanoTime()}",
        )
        check(filesDir.mkdirs())
        try {
            val sealer = ReplayUnavailableSealer()
            val adapter = SenderIndexBundlePersistenceAdapter(
                dev.hryshyn.remanence.index.SenderIndexBundleStager(
                    AccountScopedFileRoots(filesDir),
                    sealer,
                ),
            )
            val validRequest = IncomingVerifiedControlIndexPersistenceRequest(
                ownerUserId = owner,
                capsuleId = capsule,
                verified = IncomingVerifiedControlIndexPayload(
                    statement = PublishStatement.getDefaultInstance(),
                    recognition = validRecognition(),
                    senderVerification = TestSenderVerification.forCapsule(capsule),
                ),
            )

            assertSame(
                IncomingVerifiedControlIndexPersistenceResult.Durable,
                adapter.persist(validRequest, owner),
            )
            sealer.unsealUnavailable = true
            assertEquals(
                IncomingVerifiedControlIndexPersistenceResult.Retryable(
                    IncomingVerifiedControlIndexPersistenceRetryReason.DEPENDENCY_UNAVAILABLE,
                ),
                adapter.persist(validRequest, owner),
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

    private fun validRecognition() = recognition.copy(
        frontFingerprint = fingerprint(FingerprintSide.FRONT),
    )

    private fun fingerprint(side: FingerprintSide): ByteArray = FingerprintCodec.serialize(
        PostcardFingerprint(
            profileId = RecognitionProfile.MVP_ORB_V1_ID,
            canonicalWidthPx = 1200,
            canonicalHeightPx = 800,
            coarseHash64 = 17L,
            keypoints = listOf(
                FingerprintKeypoint(
                    xNormalized = 0.5,
                    yNormalized = 0.5,
                    scaleNormalized = 1.0,
                    angleCentiDegrees = 9000,
                    responseQuantized = 2,
                    octave = 0,
                ),
            ),
            descriptors = listOf(ByteArray(FingerprintCodec.DESCRIPTOR_BYTES) { 3 }),
            quality = ExtractionQuality(
                blurScore = 1.0,
                exposureScore = 1.0,
                glareFraction = 0.1,
                detectedAreaRatio = 0.5,
            ),
        ),
    )
}

private class ReplayUnavailableSealer : SecretSealer {
    var unsealUnavailable = false

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = plaintext.copyOf()

    override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray {
        if (unsealUnavailable) error("injected provider unavailable")
        return ciphertext.copyOf()
    }
}
