package dev.hryshyn.remanence.sync

import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.crypto.RecognitionManifestCodec
import dev.hryshyn.remanence.core.data.fingerprints.SecretSealer
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.TestSenderVerification
import dev.hryshyn.remanence.core.recognition.ExtractionQuality
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintKeypoint
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.PostcardFingerprint
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import dev.hryshyn.remanence.index.SenderIndexBundleCodec
import dev.hryshyn.remanence.index.SenderIndexBundlePlaintext
import dev.hryshyn.remanence.index.SenderIndexBundleReader
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SenderIndexBundleInspectionAdapterTest {

    private val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000ba01")
    private val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ba02")
    private lateinit var filesDir: File
    private lateinit var roots: AccountScopedFileRoots

    @Before
    fun setUp() {
        filesDir = File(
            System.getProperty("java.io.tmpdir"),
            "remanence-sender-index-adapter-${System.nanoTime()}",
        )
        check(filesDir.mkdirs())
        roots = AccountScopedFileRoots(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun productionAdapterMapsAvailableMissingInvalidAndUnavailableRedacted() = runBlocking {
        val encoded = validEncoded()
        destination().apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val available = SenderIndexBundleInspectionAdapter(
            SenderIndexBundleReader(roots, FixedSealer(encoded)),
        ).inspect(request())
        assertTrue(available is IncomingSenderIndexBundleInspectionResult.Available)
        val availableResult = available as IncomingSenderIndexBundleInspectionResult.Available
        assertFalse(available.toString().contains(filesDir.path))
        availableResult.snapshot.close()
        encoded.fill(0)

        destination().delete()
        assertEquals(
            IncomingSenderIndexBundleInspectionResult.Missing,
            SenderIndexBundleInspectionAdapter(
                SenderIndexBundleReader(roots, FixedSealer(byteArrayOf(8, 1))),
            ).inspect(request()),
        )

        destination().apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(2))
        }
        assertEquals(
            IncomingSenderIndexBundleInspectionResult.Invalid,
            SenderIndexBundleInspectionAdapter(
                SenderIndexBundleReader(roots, FixedSealer(byteArrayOf(8, 1))),
            ).inspect(request()),
        )
        assertEquals(
            IncomingSenderIndexBundleInspectionResult.Unavailable(
                IncomingSenderIndexBundleInspectionUnavailableReason.SEALER_UNAVAILABLE,
            ),
            SenderIndexBundleInspectionAdapter(
                SenderIndexBundleReader(roots, ThrowingSealer()),
            ).inspect(request()),
        )
    }

    @Test
    fun adapterPreservesReaderCancellationAndOwnerBinding() = runBlocking {
        val noOwner = SenderIndexBundleInspectionAdapter(
            SenderIndexBundleReader(roots, ThrowingSealer()),
        ).inspect(
            IncomingSenderIndexBundleInspectionRequest(null, owner, capsule),
        )
        assertEquals(IncomingSenderIndexBundleInspectionResult.Invalid, noOwner)
        assertFalse(destination().exists())

        destination().apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(3))
        }
        val cancellation = CancellationException("test cancellation")
        val thrown = try {
            SenderIndexBundleInspectionAdapter(
                SenderIndexBundleReader(roots, ThrowingSealer(cancellation)),
            ).inspect(request())
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue(thrown === cancellation || thrown?.cause === cancellation)
    }

    private fun request() = IncomingSenderIndexBundleInspectionRequest(owner, owner, capsule)

    private fun destination(): File = File(
        roots.child(owner, AccountScopedFileRoots.ChildRoot.FINGERPRINTS),
        "capsules/${capsule.toRestString()}.index.bundle",
    )

    private fun validEncoded(): ByteArray {
        val recognition = RecognitionManifestContent(
            manifestVersion = RecognitionManifestCodec.FORMAT_VERSION,
            capsuleIdRaw = capsule.toProtoBytes().toByteArray(),
            senderHandleSnapshot = "adapter_sender",
            createdAtEpochSeconds = 1_700_000_001L,
            placeLabel = "adapter_place",
            frontFingerprint = fingerprint(FingerprintSide.FRONT),
        )
        val plaintext = SenderIndexBundlePlaintext.fromVerifiedRecognition(
            capsule,
            recognition,
            TestSenderVerification.forCapsule(capsule),
        )
        return try {
            SenderIndexBundleCodec().encode(plaintext)
        } finally {
            plaintext.wipe()
        }
    }

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

private class FixedSealer(
    private val opened: ByteArray,
    private val failure: Throwable? = null,
) : SecretSealer {
    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = error("unused")

    override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray {
        failure?.let { throw it }
        return opened.copyOf()
    }
}

private class ThrowingSealer(
    private val failure: Throwable = IllegalStateException("provider unavailable"),
) : SecretSealer {
    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = error("unused")

    override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray = throw failure
}
