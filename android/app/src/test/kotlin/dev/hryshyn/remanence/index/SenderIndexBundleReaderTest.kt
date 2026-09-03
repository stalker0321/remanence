package dev.hryshyn.remanence.index

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
import dev.hryshyn.remanence.core.recognition.PostcardFingerprint
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SenderIndexBundleReaderTest {

    private val owner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000b201")
    private val otherOwner = UserId.parseRest("0198f0a0-0000-7000-8000-00000000b202")
    private val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000b211")
    private val otherCapsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000b212")
    private lateinit var filesDir: File
    private lateinit var roots: AccountScopedFileRoots

    @Before
    fun setUp() {
        filesDir = File(
            System.getProperty("java.io.tmpdir"),
            "remanence-sender-index-reader-${System.nanoTime()}",
        )
        check(filesDir.mkdirs())
        roots = AccountScopedFileRoots(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun nullAndWrongOwnerRejectBeforeAnyFilesystemOperation() = runBlocking {
        val fileSystem = ReaderRecordingFileSystem()
        val reader = reader(AesGcmSealer(), fileSystem)

        val noOwner = reader.inspect(readRequest(null, owner, capsule))
        val mismatch = reader.inspect(readRequest(otherOwner, owner, capsule))

        assertEquals(
            SenderIndexBundleReadResult.Corrupt(
                SenderIndexBundleReadCorruptReason.NO_AUTHENTICATED_OWNER,
            ),
            noOwner,
        )
        assertEquals(
            SenderIndexBundleReadResult.Corrupt(
                SenderIndexBundleReadCorruptReason.OWNER_MISMATCH,
            ),
            mismatch,
        )
        assertEquals(0, fileSystem.attributeCalls)
        assertEquals(0, fileSystem.readCalls)
    }

    @Test
    fun validRealA12aBundleIsReadableAfterProcessReconstructionAndWipesBuffers() = runBlocking {
        val sealer = AesGcmSealer()
        val staged = SenderIndexBundleStager(roots, sealer).stage(
            SenderIndexBundleStageRequest(
                owner,
                owner,
                capsule,
                recognition(),
                TestSenderVerification.forCapsule(capsule),
            ),
        ) as SenderIndexBundleStageResult.Staged
        val bytesBefore = destination(owner, capsule).readBytes()
        val wiped = mutableListOf<ByteArray>()
        val wipedChars = mutableListOf<CharArray>()
        val reader = reader(
            sealer = sealer,
            wipe = { bytes ->
                wiped += bytes
                bytes.fill(0)
            },
            wipeChars = { chars ->
                wipedChars += chars
                chars.fill('\u0000')
            },
        )

        val first = reader.inspect(readRequest(owner, owner, capsule))
        val snapshot = (first as SenderIndexBundleReadResult.Available).snapshot
        val callerOwnedHandle = snapshot.senderHandleSnapshot
        val callerOwnedPlace = snapshot.placeLabel
        assertEquals(SenderIndexBundleCodec.FORMAT_VERSION, snapshot.localFormatVersion)
        assertEquals(capsule, snapshot.capsuleId)
        assertEquals(recognition().senderHandleSnapshot, callerOwnedHandle)
        assertEquals(recognition().placeLabel, callerOwnedPlace)
        assertEquals(recognition().createdAtEpochSeconds, snapshot.createdAtEpochSeconds)
        assertArrayEquals(recognition().frontFingerprint, snapshot.frontFingerprint)
        val front = snapshot.frontFingerprint
        front[0] = (front[0] + 1).toByte()
        assertFalse(front.contentEquals(snapshot.frontFingerprint))
        assertFalse(first.toString().contains("alice_1"))
        assertFalse(first.toString().contains(filesDir.path))
        snapshot.close()
        snapshot.close()
        assertEquals("alice_1", callerOwnedHandle)
        assertEquals("Paris", callerOwnedPlace)
        listOf<() -> Any?>(
            { snapshot.localFormatVersion },
            { snapshot.capsuleId },
            { snapshot.senderHandleSnapshot },
            { snapshot.createdAtEpochSeconds },
            { snapshot.placeLabel },
            { snapshot.frontFingerprint },
        ).forEach(::assertThrowsIllegalState)
        assertTrue(wipedChars.isNotEmpty())
        assertTrue(wipedChars.all { chars -> chars.all { it == '\u0000' } })

        val reconstructed = SenderIndexBundleReader(roots, sealer)
            .inspect(readRequest(owner, owner, capsule))
        val reconstructedSnapshot =
            (reconstructed as SenderIndexBundleReadResult.Available).snapshot
        assertEquals(capsule, reconstructedSnapshot.capsuleId)
        reconstructedSnapshot.close()

        assertTrue(staged.durable.asFile().isFile)
        assertArrayEquals(bytesBefore, destination(owner, capsule).readBytes())
        assertTrue(wiped.isNotEmpty())
        assertTrue(wiped.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun oldAadIsNotAttemptedAfterTheBreakingReset() = runBlocking {
        val sealer = AesGcmSealer()
        val codec = SenderIndexBundleCodec()

        val current = SenderIndexBundlePlaintext.fromVerifiedRecognition(
            capsule,
            recognition(),
            TestSenderVerification.forCapsule(capsule),
        )
        val currentBytes = codec.encode(current)
        val oldAadCiphertext = sealer.seal(
            currentBytes,
            SenderIndexBundleAad.encode(
                owner,
                capsule,
                SenderIndexBundleCodec.FORMAT_VERSION - 1,
            ),
        )
        writeDestination(owner, capsule, oldAadCiphertext)
        currentBytes.fill(0)
        oldAadCiphertext.fill(0)
        current.wipe()

        assertEquals(
            SenderIndexBundleReadResult.Unavailable(
                SenderIndexBundleReadUnavailableReason.SEALER_UNAVAILABLE,
            ),
            reader(sealer).inspect(readRequest(owner, owner, capsule)),
        )
    }

    @Test
    fun missingBundleIsDistinctAndReaderDoesNotCreateAnything() = runBlocking {
        val result = SenderIndexBundleReader(roots, AesGcmSealer())
            .inspect(readRequest(owner, owner, capsule))

        assertSame(SenderIndexBundleReadResult.Missing, result)
        assertFalse(destination(owner, capsule).exists())
    }

    @Test
    fun embeddedCapsuleMismatchIsTerminalAndWrongAadIsConservativeUnavailable() = runBlocking {
        val sealer = AesGcmSealer()
        val plaintext = SenderIndexBundlePlaintext.fromVerifiedRecognition(
            capsule,
            recognition(),
            TestSenderVerification.forCapsule(capsule),
        )
        val encoded = SenderIndexBundleCodec().encode(plaintext)
        plaintext.wipe()
        val encrypted = sealer.seal(
            encoded,
            SenderIndexBundleAad.encode(owner, otherCapsule, SenderIndexBundleCodec.FORMAT_VERSION),
        )
        encoded.fill(0)
        writeDestination(owner, otherCapsule, encrypted)
        encrypted.fill(0)

        val mismatch = SenderIndexBundleReader(roots, sealer)
            .inspect(readRequest(owner, owner, otherCapsule))
        assertEquals(
            SenderIndexBundleReadResult.Corrupt(SenderIndexBundleReadCorruptReason.CAPSULE_MISMATCH),
            mismatch,
        )

        val valid = SenderIndexBundleStager(roots, sealer).stage(
            SenderIndexBundleStageRequest(
                owner,
                owner,
                capsule,
                recognition(),
                TestSenderVerification.forCapsule(capsule),
            ),
        ) as SenderIndexBundleStageResult.Staged
        assertTrue(valid.durable.asFile().isFile)
        val validBytes = valid.durable.asFile().readBytes()
        val wrongAadCiphertext = sealer.seal(
            encoded,
            SenderIndexBundleAad.encode(owner, otherCapsule, SenderIndexBundleCodec.FORMAT_VERSION),
        )
        writeDestination(owner, capsule, wrongAadCiphertext)
        wrongAadCiphertext.fill(0)
        assertEquals(
            SenderIndexBundleReadResult.Unavailable(
                SenderIndexBundleReadUnavailableReason.SEALER_UNAVAILABLE,
            ),
            SenderIndexBundleReader(roots, sealer)
                .inspect(readRequest(owner, owner, capsule)),
        )
        writeDestination(owner, capsule, validBytes)
        val refusing = AasRecordingUnavailableSealer()
        val unavailable = SenderIndexBundleReader(roots, refusing)
            .inspect(readRequest(owner, owner, capsule))
        assertEquals(
            SenderIndexBundleReadResult.Unavailable(
                SenderIndexBundleReadUnavailableReason.SEALER_UNAVAILABLE,
            ),
            unavailable,
        )
        assertArrayEquals(
            SenderIndexBundleAad.encode(owner, capsule, SenderIndexBundleCodec.FORMAT_VERSION),
            refusing.aad,
        )
    }

    @Test
    fun symlinkLeafAndAncestorAreRejectedWithoutTouchingTarget() = runBlocking {
        val leafTarget = File(filesDir, "leaf-target").apply { writeText("sentinel") }
        val leaf = destination(owner, capsule).apply {
            parentFile!!.mkdirs()
            Files.createSymbolicLink(toPath(), leafTarget.toPath())
        }
        val leafResult = SenderIndexBundleReader(roots, AesGcmSealer())
            .inspect(readRequest(owner, owner, capsule))
        assertEquals(
            SenderIndexBundleReadResult.Corrupt(SenderIndexBundleReadCorruptReason.PATH_UNSAFE),
            leafResult,
        )
        assertEquals("sentinel", leafTarget.readText())
        Files.deleteIfExists(leaf.toPath())
        File(
            roots.child(owner, AccountScopedFileRoots.ChildRoot.FINGERPRINTS),
            "capsules",
        ).delete()

        val ancestorTarget = File(filesDir, "ancestor-target").apply { mkdirs() }
        val capsules = File(
            roots.child(owner, AccountScopedFileRoots.ChildRoot.FINGERPRINTS),
            "capsules",
        )
        Files.createSymbolicLink(capsules.toPath(), ancestorTarget.toPath())
        val ancestorResult = SenderIndexBundleReader(roots, AesGcmSealer())
            .inspect(readRequest(owner, owner, capsule))
        assertEquals(
            SenderIndexBundleReadResult.Corrupt(SenderIndexBundleReadCorruptReason.PATH_UNSAFE),
            ancestorResult,
        )
        assertTrue(ancestorTarget.isDirectory)
    }

    @Test
    fun zeroOversizedAndNonRegularFilesAreTerminalAndUntouched() = runBlocking {
        val zero = destination(owner, capsule).apply {
            parentFile!!.mkdirs()
            createNewFile()
        }
        assertEquals(
            SenderIndexBundleReadResult.Corrupt(SenderIndexBundleReadCorruptReason.CIPHERTEXT_EMPTY),
            SenderIndexBundleReader(roots, AesGcmSealer()).inspect(readRequest(owner, owner, capsule)),
        )
        zero.delete()

        val oversized = destination(owner, capsule).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(MAX_CIPHERTEXT_BYTES + 1))
        }
        assertEquals(
            SenderIndexBundleReadResult.Corrupt(SenderIndexBundleReadCorruptReason.CIPHERTEXT_TOO_LARGE),
            SenderIndexBundleReader(roots, AesGcmSealer()).inspect(readRequest(owner, owner, capsule)),
        )
        assertEquals((MAX_CIPHERTEXT_BYTES + 1).toLong(), oversized.length())
        oversized.delete()

        val directory = destination(owner, capsule).apply {
            parentFile!!.mkdirs()
            mkdir()
        }
        assertEquals(
            SenderIndexBundleReadResult.Corrupt(SenderIndexBundleReadCorruptReason.NON_REGULAR_FILE),
            SenderIndexBundleReader(roots, AesGcmSealer()).inspect(readRequest(owner, owner, capsule)),
        )
        assertTrue(directory.isDirectory)
    }

    @Test
    fun malformedPlaintextIsCorruptButCiphertextCorruptionAndTruncationStayRetryable() = runBlocking {
        val sealer = AesGcmSealer()
        val malformed = sealer.seal(
            byteArrayOf(0x08, 0x01),
            SenderIndexBundleAad.encode(owner, capsule, SenderIndexBundleCodec.FORMAT_VERSION),
        )
        writeDestination(owner, capsule, malformed)
        malformed.fill(0)
        assertEquals(
            SenderIndexBundleReadResult.Corrupt(SenderIndexBundleReadCorruptReason.PLAINTEXT_MALFORMED),
            SenderIndexBundleReader(roots, sealer).inspect(readRequest(owner, owner, capsule)),
        )
        destination(owner, capsule).delete()

        val staged = SenderIndexBundleStager(roots, sealer).stage(
            SenderIndexBundleStageRequest(
                owner,
                owner,
                capsule,
                recognition(),
                TestSenderVerification.forCapsule(capsule),
            ),
        ) as SenderIndexBundleStageResult.Staged
        val validBytes = staged.durable.asFile().readBytes()
        writeDestination(owner, capsule, ByteArray(validBytes.size) { 3 })
        assertEquals(
            SenderIndexBundleReadResult.Unavailable(
                SenderIndexBundleReadUnavailableReason.SEALER_UNAVAILABLE,
            ),
            SenderIndexBundleReader(roots, sealer).inspect(readRequest(owner, owner, capsule)),
        )

        writeDestination(owner, capsule, validBytes.copyOf(validBytes.size - 1))
        assertEquals(
            SenderIndexBundleReadResult.Unavailable(
                SenderIndexBundleReadUnavailableReason.SEALER_UNAVAILABLE,
            ),
            SenderIndexBundleReader(roots, sealer).inspect(readRequest(owner, owner, capsule)),
        )
    }

    @Test
    fun boundedStreamingRejectsTruncatedZeroReadAndOverflowWithoutMutation() = runBlocking {
        val sealer = AesGcmSealer()
        SenderIndexBundleStager(roots, sealer).stage(
            SenderIndexBundleStageRequest(
                owner,
                owner,
                capsule,
                recognition(),
                TestSenderVerification.forCapsule(capsule),
            ),
        )
        val original = destination(owner, capsule).readBytes()
        val expectedSize = original.size.toLong()

        val truncatedWipes = mutableListOf<ByteArray>()
        val truncatedFileSystem = ScriptedReaderFileSystem(
            target = destination(owner, capsule).toPath(),
            reportedSize = expectedSize,
            streamFactory = { original.copyOf(original.size - 1).inputStream() },
        )
        assertEquals(
            SenderIndexBundleReadResult.Corrupt(
                SenderIndexBundleReadCorruptReason.CIPHERTEXT_TRUNCATED,
            ),
            reader(
                sealer = sealer,
                fileSystem = truncatedFileSystem,
                wipe = { bytes ->
                    truncatedWipes += bytes
                    bytes.fill(0)
                },
            ).inspect(readRequest(owner, owner, capsule)),
        )
        assertArrayEquals(original, destination(owner, capsule).readBytes())
        assertTrue(truncatedWipes.isNotEmpty())
        assertTrue(truncatedWipes.all { bytes -> bytes.all { it == 0.toByte() } })

        val zeroReadWipes = mutableListOf<ByteArray>()
        val zeroReadFileSystem = ScriptedReaderFileSystem(
            target = destination(owner, capsule).toPath(),
            reportedSize = expectedSize,
            streamFactory = { ZeroReadInputStream() },
        )
        assertEquals(
            SenderIndexBundleReadResult.Unavailable(
                SenderIndexBundleReadUnavailableReason.LOCAL_STORAGE,
            ),
            reader(
                sealer = sealer,
                fileSystem = zeroReadFileSystem,
                wipe = { bytes ->
                    zeroReadWipes += bytes
                    bytes.fill(0)
                },
            ).inspect(readRequest(owner, owner, capsule)),
        )
        assertEquals(1, zeroReadFileSystem.readCount)
        assertArrayEquals(original, destination(owner, capsule).readBytes())
        assertTrue(zeroReadWipes.isNotEmpty())
        assertTrue(zeroReadWipes.all { bytes -> bytes.all { it == 0.toByte() } })

        val overflowWipes = mutableListOf<ByteArray>()
        val overflowFileSystem = ScriptedReaderFileSystem(
            target = destination(owner, capsule).toPath(),
            reportedSize = expectedSize,
            streamFactory = { (original + byteArrayOf(9)).inputStream() },
        )
        assertEquals(
            SenderIndexBundleReadResult.Corrupt(
                SenderIndexBundleReadCorruptReason.CIPHERTEXT_SIZE_CHANGED,
            ),
            reader(
                sealer = sealer,
                fileSystem = overflowFileSystem,
                wipe = { bytes ->
                    overflowWipes += bytes
                    bytes.fill(0)
                },
            ).inspect(readRequest(owner, owner, capsule)),
        )
        assertArrayEquals(original, destination(owner, capsule).readBytes())
        assertTrue(overflowWipes.isNotEmpty())
        assertTrue(overflowWipes.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun readFailureIsRetryableAndCancellationIdentityPropagates() = runBlocking {
        val sealer = AesGcmSealer()
        SenderIndexBundleStager(roots, sealer).stage(
            SenderIndexBundleStageRequest(
                owner,
                owner,
                capsule,
                recognition(),
                TestSenderVerification.forCapsule(capsule),
            ),
        )
        val failingReader = SenderIndexBundleReader(
            roots = roots,
            sealer = sealer,
            codec = SenderIndexBundleCodec(),
            fileSystem = FailingReadFileSystem(destination(owner, capsule).toPath()),
            wipe = { it.fill(0) },
        )
        assertEquals(
            SenderIndexBundleReadResult.Unavailable(
                SenderIndexBundleReadUnavailableReason.LOCAL_STORAGE,
            ),
            failingReader.inspect(readRequest(owner, owner, capsule)),
        )

        val cancellation = CancellationException("private cancellation")
        val cancellationReader = SenderIndexBundleReader(
            roots = roots,
            sealer = object : SecretSealer {
                override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray =
                    error("unused")

                override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray =
                    throw cancellation
            },
        )
        var thrown: Throwable? = null
        try {
            cancellationReader.inspect(readRequest(owner, owner, capsule))
        } catch (failure: Throwable) {
            thrown = failure
        }
        assertTrue(thrown === cancellation || thrown?.cause === cancellation)
    }

    @Test
    fun requestResultsAndSnapshotAreRedacted() = runBlocking {
        val request = readRequest(owner, owner, capsule)
        val result = SenderIndexBundleReader(roots, AesGcmSealer())
            .inspect(request)
        assertFalse(request.toString().contains(owner.toRestString()))
        assertFalse(request.toString().contains(capsule.toRestString()))
        assertFalse(result.toString().contains(filesDir.path))
        assertFalse(result.toString().contains("alice_1"))
        assertFalse(result.toString().contains("Paris"))
    }

    private fun reader(
        sealer: SecretSealer,
        fileSystem: SenderIndexBundleReaderFileSystem = RealTestFileSystem,
        wipe: (ByteArray) -> Unit = { it.fill(0) },
        wipeChars: (CharArray) -> Unit = { it.fill('\u0000') },
    ) = SenderIndexBundleReader(
        roots = roots,
        sealer = sealer,
        codec = SenderIndexBundleCodec(),
        fileSystem = fileSystem,
        wipe = wipe,
        wipeChars = wipeChars,
    )

    private fun readRequest(
        authenticatedOwner: UserId?,
        ownerUserId: UserId,
        capsuleId: CapsuleId,
    ) = SenderIndexBundleReadRequest(authenticatedOwner, ownerUserId, capsuleId)

    private fun destination(ownerUserId: UserId, capsuleId: CapsuleId): File = File(
        roots.child(ownerUserId, AccountScopedFileRoots.ChildRoot.FINGERPRINTS),
        "capsules/${capsuleId.toRestString()}.index.bundle",
    )

    private fun writeDestination(ownerUserId: UserId, capsuleId: CapsuleId, bytes: ByteArray) {
        val file = destination(ownerUserId, capsuleId)
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
    }

    private fun recognition(capsuleId: CapsuleId = capsule) = RecognitionManifestContent(
        protocolVersion = RecognitionManifestCodec.FORMAT_VERSION,
        capsuleIdRaw = capsuleId.toProtoBytes().toByteArray(),
        senderHandleSnapshot = "alice_1",
        createdAtEpochSeconds = 1_700_000_000L,
        placeLabel = "Paris",
        frontFingerprint = fingerprint(),
    )

    private fun fingerprint(): ByteArray = FingerprintCodec.serialize(
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

    private fun assertThrowsIllegalState(block: () -> Any?) {
        try {
            block()
            throw AssertionError("expected failure")
        } catch (failure: Throwable) {
            if (failure !is IllegalStateException) throw failure
        }
    }

    private companion object {
        const val MAX_CIPHERTEXT_BYTES = SenderIndexBundleCodec.MAX_PLAINTEXT_BYTES +
            ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES.toInt()
    }
}

private object RealTestFileSystem : SenderIndexBundleReaderFileSystem {
    override fun attributes(path: Path): SenderIndexBundleFileAttributes? = try {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        SenderIndexBundleFileAttributes(
            isSymbolicLink = attributes.isSymbolicLink,
            isRegularFile = attributes.isRegularFile,
            isDirectory = attributes.isDirectory,
            size = attributes.size(),
        )
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    override fun openRead(path: Path): InputStream =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
}

private class ReaderRecordingFileSystem : SenderIndexBundleReaderFileSystem {
    var attributeCalls = 0
    var readCalls = 0

    override fun attributes(path: Path): SenderIndexBundleFileAttributes? {
        attributeCalls += 1
        return null
    }

    override fun openRead(path: Path): InputStream {
        readCalls += 1
        throw AssertionError("reader must not open an unauthenticated path")
    }
}

private class FailingReadFileSystem(private val failingPath: Path) : SenderIndexBundleReaderFileSystem {
    override fun attributes(path: Path): SenderIndexBundleFileAttributes? =
        RealTestFileSystem.attributes(path)

    override fun openRead(path: Path): InputStream {
        if (path == failingPath) throw IOException("injected read failure")
        return RealTestFileSystem.openRead(path)
    }
}

private class ScriptedReaderFileSystem(
    private val target: Path,
    private val reportedSize: Long,
    private val streamFactory: () -> InputStream,
) : SenderIndexBundleReaderFileSystem {
    var readCount = 0

    override fun attributes(path: Path): SenderIndexBundleFileAttributes? =
        if (path == target) {
            SenderIndexBundleFileAttributes(
                isSymbolicLink = false,
                isRegularFile = true,
                isDirectory = false,
                size = reportedSize,
            )
        } else {
            RealTestFileSystem.attributes(path)
        }

    override fun openRead(path: Path): InputStream {
        readCount += 1
        return streamFactory()
    }
}

private class ZeroReadInputStream : InputStream() {
    override fun read(): Int = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
}

private open class AesGcmSealer : SecretSealer {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val random = SecureRandom()

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plaintext)
    }

    override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(ciphertext.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOf(12)))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
    }
}

private class AasRecordingUnavailableSealer : SecretSealer {
    var aad: ByteArray = ByteArray(0)

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = error("unused")

    override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray {
        this.aad = aad.copyOf()
        throw IllegalStateException("provider unavailable")
    }
}
