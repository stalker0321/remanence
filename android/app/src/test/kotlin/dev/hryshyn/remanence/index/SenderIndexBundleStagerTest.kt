package dev.hryshyn.remanence.index

import dev.hryshyn.remanence.core.crypto.RecognitionManifestContent
import dev.hryshyn.remanence.core.data.fingerprints.SecretSealer
import dev.hryshyn.remanence.core.data.storage.AccountScopedFileRoots
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import dev.hryshyn.remanence.core.recognition.ExtractionQuality
import dev.hryshyn.remanence.core.recognition.FingerprintCodec
import dev.hryshyn.remanence.core.recognition.FingerprintKeypoint
import dev.hryshyn.remanence.core.recognition.FingerprintSide
import dev.hryshyn.remanence.core.recognition.PostcardFingerprint
import dev.hryshyn.remanence.core.recognition.RecognitionProfile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SenderIndexBundleStagerTest {

    private val ownerA = UserId.parseRest("0198f0a0-0000-7000-8000-0000000000a1")
    private val ownerB = UserId.parseRest("0198f0a0-0000-7000-8000-0000000000b1")
    private val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca11")
    private lateinit var filesDir: File
    private lateinit var roots: AccountScopedFileRoots

    @Before
    fun setUp() {
        filesDir = File(
            System.getProperty("java.io.tmpdir"),
            "remanence-sender-index-${System.nanoTime()}",
        )
        check(filesDir.mkdirs())
        roots = AccountScopedFileRoots(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun codecRoundTripIsDeterministicAndRejectsUnknownOrInvalidData() {
        val content = recognition()
        val plaintext = SenderIndexBundlePlaintext.fromVerifiedRecognition(capsule, content)
        val codec = SenderIndexBundleCodec()
        val first = codec.encode(plaintext)
        val second = codec.encode(plaintext)

        assertArrayEquals(first, second)
        val decoded = codec.decode(first)
        assertEquals(plaintext.capsuleId, decoded.capsuleId)
        assertEquals(plaintext.senderHandleSnapshot, decoded.senderHandleSnapshot)
        assertEquals(plaintext.placeLabel, decoded.placeLabel)
        assertArrayEquals(plaintext.frontFingerprint, decoded.frontFingerprint)
        assertArrayEquals(plaintext.backFingerprint, decoded.backFingerprint)
        assertTrue(decoded.semanticallyEquals(plaintext))
        assertThrows<IllegalArgumentException> {
            codec.decode(first + byteArrayOf(0x48, 0x01))
        }
        assertThrows<IllegalArgumentException> {
            codec.decode(first + byteArrayOf(0x08, 0x01))
        }
        assertThrows<IllegalArgumentException> {
            codec.decode(first.copyOf().also { it[1] = 2 })
        }
        assertThrows<IllegalArgumentException> {
            codec.decode(byteArrayOf(0x0a, 0x01))
        }
        assertThrows<IllegalArgumentException> {
            SenderIndexBundlePlaintext.fromVerifiedRecognition(
                capsule,
                content.copy(placeLabel = ""),
            )
        }
        assertThrows<IllegalArgumentException> {
            SenderIndexBundlePlaintext.fromVerifiedRecognition(
                capsule,
                content.copy(frontFingerprint = ByteArray(0)),
            )
        }
        assertThrows<IllegalArgumentException> {
            SenderIndexBundlePlaintext.fromVerifiedRecognition(
                capsule,
                content.copy(protocolVersion = ProtocolV1Limits.PROTOCOL_VERSION + 1),
            )
        }
        assertThrows<IllegalArgumentException> {
            SenderIndexBundlePlaintext.fromVerifiedRecognition(
                capsule,
                content.copy(senderHandleSnapshot = "a".repeat(ProtocolV1Limits.HANDLE_MAX_ASCII_CHARS + 1)),
            )
        }
        assertThrows<IllegalArgumentException> {
            SenderIndexBundlePlaintext.fromVerifiedRecognition(
                capsule,
                content.copy(placeLabel = "x".repeat(ProtocolV1Limits.PLACE_LABEL_MAX_UTF8_BYTES + 1)),
            )
        }
        assertThrows<IllegalArgumentException> {
            SenderIndexBundlePlaintext.fromVerifiedRecognition(
                capsule,
                content.copy(frontFingerprint = ByteArray(SenderIndexBundleCodec.MAX_FINGERPRINT_BYTES + 1)),
            )
        }
    }

    @Test
    fun freshStageBindsOwnerCapsuleAndKeepsPlaintextOutOfCiphertext() = runBlocking<Unit> {
        val sealer = RandomAuthenticatedSealer()
        val result = SenderIndexBundleStager(roots, sealer).stage(request(ownerA))

        assertTrue(result is SenderIndexBundleStageResult.Staged)
        val staged = result as SenderIndexBundleStageResult.Staged
        assertFalse(staged.replayed)
        assertEquals(ownerA, staged.durable.ownerUserId)
        assertEquals(capsule, staged.durable.capsuleId)
        val final = staged.durable.asFile()
        assertTrue(final.isFile)
        val ciphertext = final.readBytes()
        assertFalse(ciphertext.toString(Charsets.UTF_8).contains("alice_1"))
        assertFalse(ciphertext.toString(Charsets.UTF_8).contains("Paris"))
        assertFalse(ciphertext.contentEquals(contentBytes(FingerprintSide.FRONT)))
        assertFalse(ciphertext.containsBytes(contentBytes(FingerprintSide.FRONT)))
        assertEquals(ciphertext.size.toLong(), staged.durable.ciphertextSizeBytes)
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(ciphertext),
            staged.durable.ciphertextSha256,
        )
        assertFalse(staged.durable.ciphertextSha256.all { it == 0.toByte() })
        assertFalse(staged.toString().contains(filesDir.path))
        assertFalse(staged.toString().contains(ownerA.toRestString()))

        assertThrows<Exception> {
            sealer.unseal(
                ciphertext,
                SenderIndexBundleAad.encode(ownerB, capsule, SenderIndexBundleCodec.FORMAT_VERSION),
            )
        }
        assertThrows<Exception> {
            sealer.unseal(
                ciphertext,
                SenderIndexBundleAad.encode(ownerA, capsule, SenderIndexBundleCodec.FORMAT_VERSION + 1),
            )
        }
        assertThrows<Exception> {
            sealer.unseal(
                ciphertext,
                SenderIndexBundleAad.encode(
                    ownerA,
                    CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca12"),
                    SenderIndexBundleCodec.FORMAT_VERSION,
                ),
            )
        }
    }

    @Test
    fun randomizedCiphertextReplayIsSemanticAndConflictingReplayCannotReplaceWinner() = runBlocking {
        val sealer = RandomAuthenticatedSealer()
        val stager = SenderIndexBundleStager(roots, sealer)
        val first = stager.stage(request(ownerA)) as SenderIndexBundleStageResult.Staged
        val original = first.durable.asFile().readBytes()

        val replay = stager.stage(request(ownerA)) as SenderIndexBundleStageResult.Staged
        assertTrue(replay.replayed)
        assertArrayEquals(original, replay.durable.asFile().readBytes())

        val conflict = stager.stage(request(ownerA, recognition = recognition(place = "London")))
        assertEquals(
            SenderIndexBundleStageFailure.DESTINATION_CONFLICT,
            (conflict as SenderIndexBundleStageResult.Failure).reason,
        )
        assertArrayEquals(original, first.durable.asFile().readBytes())
    }

    @Test
    fun ownerIsRequiredBeforeAnyFileWorkAndWrongRootSymlinkFailsClosed() = runBlocking {
        val noOwner = SenderIndexBundleStager(roots, RandomAuthenticatedSealer()).stage(
            request(authenticatedOwner = null),
        )
        assertEquals(SenderIndexBundleStageFailure.NO_AUTHENTICATED_OWNER, (noOwner as SenderIndexBundleStageResult.Failure).reason)
        assertFalse(roots.child(ownerA, AccountScopedFileRoots.ChildRoot.FINGERPRINTS).exists())

        val mismatch = SenderIndexBundleStager(roots, RandomAuthenticatedSealer()).stage(
            request(authenticatedOwner = ownerB),
        )
        assertEquals(SenderIndexBundleStageFailure.OWNER_MISMATCH, (mismatch as SenderIndexBundleStageResult.Failure).reason)

        val fingerprints = roots.child(ownerA, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)
        val outside = File(filesDir, "outside").apply { mkdirs() }
        fingerprints.mkdirs()
        Files.createSymbolicLink(File(fingerprints, "capsules").toPath(), outside.toPath())
        val unsafe = SenderIndexBundleStager(roots, RandomAuthenticatedSealer()).stage(request(ownerA))
        assertEquals(SenderIndexBundleStageFailure.PATH_UNSAFE, (unsafe as SenderIndexBundleStageResult.Failure).reason)
        assertTrue(outside.listFiles().isNullOrEmpty())
    }

    @Test
    fun successfulConcurrentSameCapsuleStageHasOneSafeWinner() = runBlocking {
        val stager = SenderIndexBundleStager(roots, RandomAuthenticatedSealer())
        val results = listOf(
            async(Dispatchers.Default) { stager.stage(request(ownerA)) },
            async(Dispatchers.Default) { stager.stage(request(ownerA)) },
        ).awaitAll()

        assertTrue(results.all { it is SenderIndexBundleStageResult.Staged })
        val staged = results.map { it as SenderIndexBundleStageResult.Staged }
        assertTrue(staged.any { !it.replayed })
        assertTrue(staged.any { it.replayed })
        assertTrue(staged.map { it.durable.asFile().canonicalFile }.distinct().size == 1)
    }

    @Test
    fun plaintextPassedToSealerIsWipedAfterSuccessAndFailure() = runBlocking {
        val sealer = CapturingSealer(fail = false)
        SenderIndexBundleStager(roots, sealer).stage(request(ownerA))
        assertNotNull(sealer.sealedPlaintext)
        assertTrue(sealer.sealedPlaintext!!.all { it == 0.toByte() })

        val failing = CapturingSealer(fail = true)
        val result = SenderIndexBundleStager(roots, failing).stage(
            request(
                authenticatedOwner = ownerB,
                owner = ownerB,
                capsuleId = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca12"),
            ),
        )
        assertEquals(SenderIndexBundleStageFailure.SEALING_FAILED, (result as SenderIndexBundleStageResult.Failure).reason)
        assertNotNull(failing.sealedPlaintext)
        assertTrue(failing.sealedPlaintext!!.all { it == 0.toByte() })
    }

    @Test
    fun forcedDestinationFailureLeavesWinnerForSafeReplay() = runBlocking {
        val fs = RecordingFileSystem()
        val sealer = RandomAuthenticatedSealer()
        val stager = SenderIndexBundleStager(
            roots = roots,
            sealer = sealer,
            codec = SenderIndexBundleCodec(),
            fileSystem = fs,
            wipe = { it.fill(0) },
        )
        fs.failNextDestinationForce = true
        val failed = stager.stage(request(ownerA)) as SenderIndexBundleStageResult.Failure
        assertEquals(SenderIndexBundleStageFailure.LOCAL_STORAGE, failed.reason)
        val destination = roots.child(ownerA, AccountScopedFileRoots.ChildRoot.FINGERPRINTS)
            .resolve("capsules/${capsule.toRestString()}.index.bundle")
        assertTrue(destination.isFile)

        val retried = stager.stage(request(ownerA))
        assertTrue(retried is SenderIndexBundleStageResult.Staged)
        assertTrue(
            fs.events.indexOfFirst { it == "link" } <
                fs.events.indexOfFirst { it == "force-file:${destination.name}" },
        )
        assertTrue(fs.events.indexOfFirst { it.startsWith("force-dir:") } >= 0)
    }

    @Test
    fun temporaryFsyncFailureNeverLinksOrReportsSuccess() = runBlocking {
        val fs = RecordingFileSystem()
        fs.failNextFileForce = true
        val stager = SenderIndexBundleStager(
            roots = roots,
            sealer = RandomAuthenticatedSealer(),
            codec = SenderIndexBundleCodec(),
            fileSystem = fs,
            wipe = { it.fill(0) },
        )
        val result = stager.stage(request(ownerA))
        assertEquals(SenderIndexBundleStageFailure.LOCAL_STORAGE, (result as SenderIndexBundleStageResult.Failure).reason)
        assertFalse(fs.events.contains("link"))
        assertFalse(destination(ownerA).exists())
    }

    @Test
    fun zeroReadFailureWipesReturnedBuffer() = runBlocking {
        val wiped = mutableListOf<ByteArray>()
        val fs = RecordingFileSystem().apply { zeroRead = true }
        val stager = SenderIndexBundleStager(
            roots = roots,
            sealer = RandomAuthenticatedSealer(),
            codec = SenderIndexBundleCodec(),
            fileSystem = fs,
            wipe = { bytes ->
                wiped += bytes
                bytes.fill(0)
            },
        )

        val result = stager.stage(request(ownerA)) as SenderIndexBundleStageResult.Failure

        assertEquals(SenderIndexBundleStageFailure.LOCAL_STORAGE, result.reason)
        assertTrue(wiped.any { it.isNotEmpty() && it.all { byte -> byte == 0.toByte() } })
    }

    @Test
    fun writeFailureIsTypedAndDoesNotLeakPath() = runBlocking {
        val fs = RecordingFileSystem().apply { failWrite = true }
        val result = SenderIndexBundleStager(
            roots = roots,
            sealer = RandomAuthenticatedSealer(),
            codec = SenderIndexBundleCodec(),
            fileSystem = fs,
            wipe = { it.fill(0) },
        ).stage(request(ownerA))

        val failure = result as SenderIndexBundleStageResult.Failure
        assertEquals(
            SenderIndexBundleStageFailure.LOCAL_STORAGE,
            failure.reason,
        )
        assertTrue(failure.retryable)
        assertFalse(failure.toString().contains(filesDir.path))
        assertFalse(fs.events.contains("link"))
    }

    @Test
    fun outOfContractSealedSizeFailsBeforeWrite() = runBlocking {
        val fs = RecordingFileSystem()
        val oversizedSealer = object : SecretSealer {
            override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = ByteArray(
                SenderIndexBundleCodec.MAX_PLAINTEXT_BYTES +
                    ProtocolV1Limits.ARTIFACT_AEAD_OVERHEAD_BYTES.toInt() + 1,
            )

            override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray = error("unused")
        }
        val result = SenderIndexBundleStager(
            roots = roots,
            sealer = oversizedSealer,
            codec = SenderIndexBundleCodec(),
            fileSystem = fs,
            wipe = { it.fill(0) },
        ).stage(request(ownerA))

        val failure = result as SenderIndexBundleStageResult.Failure
        assertEquals(
            SenderIndexBundleStageFailure.SEALING_FAILED,
            failure.reason,
        )
        assertFalse(failure.retryable)
        assertFalse(fs.events.contains("link"))
        assertFalse(destination(ownerA).exists())
    }

    @Test
    fun canceledStagePropagatesWithoutReturningAResult() = runBlocking {
        val job = launch {
            currentCoroutineContext().cancel()
        assertThrows<kotlinx.coroutines.CancellationException> {
                SenderIndexBundleStager(roots, RandomAuthenticatedSealer()).stage(request(ownerA))
            }
        }
        job.join()
    }

    private fun request(
        authenticatedOwner: UserId? = ownerA,
        owner: UserId = ownerA,
        capsuleId: CapsuleId = capsule,
        recognition: RecognitionManifestContent = recognition(capsuleId = capsuleId),
    ) = SenderIndexBundleStageRequest(authenticatedOwner, owner, capsuleId, recognition)

    private fun recognition(
        place: String? = "Paris",
        capsuleId: CapsuleId = capsule,
        frontFingerprint: ByteArray = contentBytes(FingerprintSide.FRONT),
        backFingerprint: ByteArray = contentBytes(FingerprintSide.BACK),
    ) = RecognitionManifestContent(
        protocolVersion = ProtocolV1Limits.PROTOCOL_VERSION,
        capsuleIdRaw = capsuleId.toProtoBytes().toByteArray(),
        senderHandleSnapshot = "alice_1",
        createdAtEpochSeconds = 1_700_000_000L,
        placeLabel = place,
        frontFingerprint = frontFingerprint,
        backFingerprint = backFingerprint,
    )

    private fun contentBytes(side: FingerprintSide): ByteArray = FingerprintCodec.serialize(
        PostcardFingerprint(
            profileId = RecognitionProfile.MVP_ORB_V1_ID,
            side = side,
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

    private fun destination(owner: UserId): File = File(
        roots.child(owner, AccountScopedFileRoots.ChildRoot.FINGERPRINTS),
        "capsules/${capsule.toRestString()}.index.bundle",
    )

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected ${T::class.java.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        needle.isNotEmpty() && indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
}

private class RandomAuthenticatedSealer : SecretSealer {
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

private class CapturingSealer(private val fail: Boolean) : SecretSealer {
    var sealedPlaintext: ByteArray? = null

    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        sealedPlaintext = plaintext
        if (fail) error("injected seal failure")
        return ByteArray(32) { 7 }
    }

    override fun unseal(ciphertext: ByteArray, aad: ByteArray): ByteArray = error("not used")
}

private class RecordingFileSystem : SenderIndexBundleFileSystem {
    val events = mutableListOf<String>()
    var failNextFileForce = false
    var failNextDestinationForce = false
    var zeroRead = false
    var failWrite = false

    override fun attributes(path: Path): SenderIndexBundleFileAttributes? = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        SenderIndexBundleFileAttributes(
            isSymbolicLink = attributes.isSymbolicLink,
            isRegularFile = attributes.isRegularFile,
            isDirectory = attributes.isDirectory,
            size = attributes.size(),
        )
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    override fun makeDirectories(path: Path) {
        Files.createDirectories(path)
    }

    override fun openRead(path: Path): InputStream {
        val delegate = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        if (!zeroRead) return delegate
        return object : InputStream() {
            override fun read(): Int = 0

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0

            override fun close() = delegate.close()
        }
    }

    override fun openWriteNew(path: Path): OutputStream {
        if (failWrite) {
            failWrite = false
            throw IOException("injected write failure")
        }
        return Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    override fun atomicNoReplaceLink(source: Path, destination: Path) {
        events += "link"
        Files.createLink(destination, source)
    }

    override fun deleteIfExists(path: Path): Boolean {
        events += "delete"
        return Files.deleteIfExists(path)
    }

    override fun forceFile(path: Path) {
        events += "force-file:${path.fileName}"
        if (failNextFileForce || (failNextDestinationForce && path.fileName.toString().endsWith(".index.bundle"))) {
            failNextFileForce = false
            failNextDestinationForce = false
            throw java.io.IOException("injected force failure")
        }
        java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }

    override fun forceDirectory(path: Path) {
        events += "force-dir:${path.fileName}"
        java.nio.channels.FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }
}
