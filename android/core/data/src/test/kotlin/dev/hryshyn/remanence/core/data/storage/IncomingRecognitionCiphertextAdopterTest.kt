package dev.hryshyn.remanence.core.data.storage

import dev.hryshyn.remanence.core.model.BlobId
import dev.hryshyn.remanence.core.model.CapsuleId
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import dev.hryshyn.remanence.core.model.UserId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IncomingRecognitionCiphertextAdopterTest {

    private val ownerA = UserId.parseRest("0198f0a0-0000-7000-8000-0000000000a1")
    private val ownerB = UserId.parseRest("0198f0a0-0000-7000-8000-0000000000b1")
    private val capsule = CapsuleId.parseRest("0198f0a0-0000-7000-8000-00000000ca11")
    private val blob = BlobId.parseRest("0198f0a0-0000-7000-8000-00000000bb11")

    private lateinit var filesDir: File
    private lateinit var roots: AccountScopedFileRoots

    @Before
    fun setUp() {
        filesDir = File(
            System.getProperty("java.io.tmpdir"),
            "remanence-incoming-adopter-${System.nanoTime()}",
        )
        check(filesDir.mkdirs()) { "could not create test sandbox" }
        roots = AccountScopedFileRoots(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun verifiedSourceIsMovedAtTheCanonicalOwnerCapsuleBlobPath() = runBlocking {
        val bytes = "verified-recognition-ciphertext".toByteArray()
        val source = source(ownerA, "recognition.tmp", bytes)
        val result = IncomingRecognitionCiphertextAdopter(roots).adopt(request(source, bytes))
        val destination = destination(ownerA)

        assertTrue(result is IncomingRecognitionCiphertextAdoptionResult.Adopted)
        assertEquals(destination.canonicalFile, (result as IncomingRecognitionCiphertextAdoptionResult.Adopted).destination.asFile().canonicalFile)
        assertFalse(source.exists())
        assertArrayEquals(bytes, destination.readBytes())
        assertFalse(result.toString().contains(filesDir.path))
        assertFalse(result.toString().contains(ownerA.toRestString()))
    }

    @Test
    fun androidShapedTrustedRootAliasSucceedsWithRawAndCanonicalDestinationSpellings() = runBlocking {
        val aliasParent = File(filesDir.parentFile, "${filesDir.name}-alias-parent").apply { mkdirs() }
        val realFilesDir = File(aliasParent, "real-files").apply { mkdirs() }
        val rawAlias = File(aliasParent, "android-files-alias")
        Files.createSymbolicLink(rawAlias.toPath(), realFilesDir.toPath())
        val originalRoots = roots
        try {
            roots = AccountScopedFileRoots(rawAlias)
            val bytes = "android-files-alias".toByteArray()
            val source = source(ownerA, "alias.tmp", bytes)
            val expectedDestination = destination(ownerA)

            val result = IncomingRecognitionCiphertextAdopter(roots).adopt(request(source, bytes))

            assertTrue(result is IncomingRecognitionCiphertextAdoptionResult.Adopted)
            val capability = (result as IncomingRecognitionCiphertextAdoptionResult.Adopted).destination.asFile()
            assertEquals(expectedDestination.absoluteFile, capability.absoluteFile)
            assertEquals(expectedDestination.canonicalFile, capability.canonicalFile)
            assertArrayEquals(bytes, expectedDestination.readBytes())
            assertFalse(source.exists())
        } finally {
            roots = originalRoots
            aliasParent.deleteRecursively()
        }
        Unit
    }

    @Test
    fun alreadyMovedFinalIsReplayedIdempotentlyAndEverySuccessForcesTheFinalFile() = runBlocking {
        val bytes = "restart-safe".toByteArray()
        val source = source(ownerA, "first.tmp", bytes)
        val fs = RecordingFileSystem()
        val adopter = IncomingRecognitionCiphertextAdopter(roots, fs)
        assertTrue(adopter.adopt(request(source, bytes)) is IncomingRecognitionCiphertextAdoptionResult.Adopted)
        val forcedAfterFirst = fs.forceFileCalls

        val replay = adopter.adopt(request(source, bytes))

        assertTrue(replay is IncomingRecognitionCiphertextAdoptionResult.Adopted)
        assertEquals(forcedAfterFirst + 1, fs.forceFileCalls)
        assertFalse(source.exists())
        assertArrayEquals(bytes, destination(ownerA).readBytes())
    }

    @Test
    fun sameDestinationConcurrentAdoptersBothReconcileTheExactWinner() = runBlocking {
        val bytes = ByteArray(128 * 1024) { (it * 13).toByte() }
        val sourceA = source(ownerA, "a.tmp", bytes)
        val sourceB = source(ownerA, "b.tmp", bytes)
        val adopter = IncomingRecognitionCiphertextAdopter(roots)

        val results = coroutineScope {
            listOf(
                async(Dispatchers.Default) { adopter.adopt(request(sourceA, bytes)) },
                async(Dispatchers.Default) { adopter.adopt(request(sourceB, bytes)) },
            ).awaitAll()
        }

        assertTrue(results.all { it is IncomingRecognitionCiphertextAdoptionResult.Adopted })
        assertArrayEquals(bytes, destination(ownerA).readBytes())
        assertFalse(sourceA.exists())
        assertFalse(sourceB.exists())
    }

    @Test
    fun mismatchedLoserCannotOverwriteOrDeleteExistingWinner() = runBlocking {
        val winner = "winner-bytes".toByteArray()
        val loser = "different-loser".toByteArray()
        val final = destination(ownerA)
        final.parentFile!!.mkdirs()
        final.writeBytes(winner)
        val source = source(ownerA, "loser.tmp", loser)

        val result = IncomingRecognitionCiphertextAdopter(roots).adopt(request(source, loser))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_CONFLICT,
            (result as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertArrayEquals(winner, final.readBytes())
        assertTrue(source.exists())
    }

    @Test
    fun wrongOwnerSourceIsRejectedBeforeAnyDestinationMaterialization() = runBlocking {
        val bytes = "owner-b".toByteArray()
        val source = source(ownerB, "foreign.tmp", bytes)

        val result = IncomingRecognitionCiphertextAdopter(roots).adopt(request(source, bytes, ownerA))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.SOURCE_OUTSIDE_OWNER_TEMP,
            (result as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertTrue(source.exists())
        assertFalse(roots.child(ownerA, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT).exists())
    }

    @Test
    fun symlinkSourceAndDestinationParentAreRejectedWithoutFollowingThem() = runBlocking {
        val bytes = "do-not-follow".toByteArray()
        val target = source(ownerA, "real.tmp", bytes)
        val symlink = File(target.parentFile, "link.tmp")
        Files.createSymbolicLink(symlink.toPath(), target.toPath())

        val sourceResult = IncomingRecognitionCiphertextAdopter(roots)
            .adopt(request(symlink, bytes))
        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.SOURCE_PATH_UNSAFE,
            (sourceResult as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertTrue(target.exists())
        assertTrue(symlink.exists())

        val outside = File(filesDir, "outside").apply { mkdirs() }
        val incomingRoot = roots.child(ownerA, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT)
        incomingRoot.mkdirs()
        Files.createSymbolicLink(File(incomingRoot, "capsules").toPath(), outside.toPath())
        val validSource = source(ownerA, "valid.tmp", bytes)
        val parentResult = IncomingRecognitionCiphertextAdopter(roots)
            .adopt(request(validSource, bytes))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_PATH_UNSAFE,
            (parentResult as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertFalse(File(outside, capsule.toRestString()).exists())
        assertTrue(validSource.exists())
    }

    @Test
    fun destinationLeafSymlinkToOutsideIsNeverReplacedOrFollowed() = runBlocking {
        val bytes = "destination-symlink".toByteArray()
        val outside = File(filesDir.parentFile, "${filesDir.name}-destination-outside").apply { mkdirs() }
        val sentinel = File(outside, "sentinel").apply { writeBytes("outside".toByteArray()) }
        val final = destination(ownerA).apply {
            parentFile!!.mkdirs()
            Files.createSymbolicLink(toPath(), sentinel.toPath())
        }
        val source = source(ownerA, "destination-symlink.tmp", bytes)

        val result = IncomingRecognitionCiphertextAdopter(roots).adopt(request(source, bytes))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_PATH_UNSAFE,
            (result as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertTrue(Files.isSymbolicLink(final.toPath()))
        assertArrayEquals("outside".toByteArray(), sentinel.readBytes())
        assertArrayEquals(bytes, source.readBytes())
        outside.deleteRecursively()
        Unit
    }

    @Test
    fun sourceSymlinkToOutsideIsNeverFollowedOrDeleted() = runBlocking {
        val outside = File(filesDir.parentFile, "${filesDir.name}-source-outside").apply { mkdirs() }
        val sentinel = File(outside, "sentinel").apply { writeBytes("outside".toByteArray()) }
        val symlink = File(
            roots.child(ownerA, AccountScopedFileRoots.ChildRoot.TEMP),
            "outside-source.tmp",
        ).apply {
            parentFile!!.mkdirs()
            Files.createSymbolicLink(toPath(), sentinel.toPath())
        }
        val result = IncomingRecognitionCiphertextAdopter(roots)
            .adopt(request(symlink, "outside".toByteArray()))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.SOURCE_PATH_UNSAFE,
            (result as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertTrue(Files.isSymbolicLink(symlink.toPath()))
        assertArrayEquals("outside".toByteArray(), sentinel.readBytes())
        assertFalse(destination(ownerA).exists())
        outside.deleteRecursively()
        Unit
    }

    @Test
    fun sourceSizeAndHashMustMatchAndRecognitionCapIsEnforced() = runBlocking {
        val bytes = "short".toByteArray()
        val source = source(ownerA, "bad.tmp", bytes)
        val wrongSize = request(source, bytes, expectedSizeBytes = bytes.size.toLong() + 1L)
        val wrongSizeResult = IncomingRecognitionCiphertextAdopter(roots).adopt(wrongSize)
        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.SOURCE_INTEGRITY_FAILED,
            (wrongSizeResult as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertTrue(source.exists())

        val wrongHash = request(source, bytes, expectedSha256 = sha256("other".toByteArray()))
        val wrongHashResult = IncomingRecognitionCiphertextAdopter(roots).adopt(wrongHash)
        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.SOURCE_INTEGRITY_FAILED,
            (wrongHashResult as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertTrue(source.exists())

        try {
            IncomingRecognitionCiphertextAdoptionRequest(
                ownerA,
                capsule,
                blob,
                ProtocolV1Limits.RECOGNITION_MANIFEST_MAX_CIPHERTEXT_BYTES + 1L,
                ByteArray(32),
                source,
            )
            assertTrue("request above recognition cap must be rejected", false)
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }

        try {
            IncomingRecognitionCiphertextAdoptionRequest(
                ownerA,
                capsule,
                blob,
                ProtocolV1Limits.ENCRYPTED_PHOTO_MAX_CIPHERTEXT_BYTES,
                ByteArray(32),
                source,
            )
            assertTrue("legacy recognition API must reject photo-sized input", false)
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun existingCorruptFinalIsAConflictAndNeitherItNorSourceIsChanged() = runBlocking {
        val expected = "expected".toByteArray()
        val corrupt = "corrupt".toByteArray()
        val final = destination(ownerA)
        final.parentFile!!.mkdirs()
        final.writeBytes(corrupt)
        val source = source(ownerA, "source.tmp", expected)

        val result = IncomingRecognitionCiphertextAdopter(roots).adopt(request(source, expected))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_CONFLICT,
            (result as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertArrayEquals(corrupt, final.readBytes())
        assertArrayEquals(expected, source.readBytes())
    }

    @Test
    fun moveUnsupportedIsTerminalAndLeavesSourceUntouched() = runBlocking {
        val bytes = "move-required".toByteArray()
        val source = source(ownerA, "unsupported.tmp", bytes)
        val fs = RecordingFileSystem().apply { unsupportedMove = true }

        val result = IncomingRecognitionCiphertextAdopter(roots, fs).adopt(request(source, bytes))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.MOVE_UNAVAILABLE,
            (result as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertFalse((result as IncomingRecognitionCiphertextAdoptionResult.Failure).retryable)
        assertTrue(source.exists())
        assertFalse(destination(ownerA).exists())
    }

    @Test
    fun destinationAppearingAtMoveTimeWithMismatchingBytesPreservesBothEntries() = runBlocking {
        val bytes = "owned-source".toByteArray()
        val conflict = "unknown-winner".toByteArray()
        val source = source(ownerA, "move-race.tmp", bytes)
        val fs = RecordingFileSystem().apply { destinationAppearanceBytes = conflict }

        val result = IncomingRecognitionCiphertextAdopter(roots, fs).adopt(request(source, bytes))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.DESTINATION_CONFLICT,
            (result as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertArrayEquals(conflict, destination(ownerA).readBytes())
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test
    fun ambiguousMoveIOExceptionReconcilesToDurableIdempotentSuccess() = runBlocking {
        val bytes = "ambiguous-move".toByteArray()
        val source = source(ownerA, "ambiguous.tmp", bytes)
        val fs = RecordingFileSystem().apply { throwAfterMove = true }
        val adopter = IncomingRecognitionCiphertextAdopter(roots, fs)

        val first = adopter.adopt(request(source, bytes))
        val replay = adopter.adopt(request(source, bytes))

        assertTrue(first is IncomingRecognitionCiphertextAdoptionResult.Adopted)
        assertTrue(replay is IncomingRecognitionCiphertextAdoptionResult.Adopted)
        assertFalse(source.exists())
        assertArrayEquals(bytes, destination(ownerA).readBytes())
    }

    @Test
    fun mandatoryFileForceFailureKeepsPublishedDestinationAndRetryReconciles() = runBlocking {
        val bytes = "force-file".toByteArray()
        val source = source(ownerA, "force.tmp", bytes)
        val fs = RecordingFileSystem().apply { failFileForce = true }
        val adopter = IncomingRecognitionCiphertextAdopter(roots, fs)

        val first = adopter.adopt(request(source, bytes))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
            (first as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertFalse(source.exists())
        assertArrayEquals(bytes, destination(ownerA).readBytes())

        fs.failFileForce = false
        val retry = adopter.adopt(request(source, bytes))
        assertTrue(retry is IncomingRecognitionCiphertextAdoptionResult.Adopted)
        assertFalse(source.exists())
        assertArrayEquals(bytes, destination(ownerA).readBytes())
    }

    @Test
    fun unsupportedMandatoryFileForceFailsClosedWithoutDeletingSource() = runBlocking {
        val bytes = "unsupported-force".toByteArray()
        val source = source(ownerA, "unsupported-force.tmp", bytes)
        val fs = RecordingFileSystem().apply { unsupportedFileForce = true }

        val result = IncomingRecognitionCiphertextAdopter(roots, fs).adopt(request(source, bytes))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.DURABILITY_UNAVAILABLE,
            (result as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertFalse(source.exists())
        assertArrayEquals(bytes, destination(ownerA).readBytes())
    }

    @Test
    fun directoryForceFailurePreservesSourceForSafeRetry() = runBlocking {
        val bytes = "force-directory".toByteArray()
        val source = source(ownerA, "directory-force.tmp", bytes)
        val fs = RecordingFileSystem().apply { failDirectoryForce = true }
        val adopter = IncomingRecognitionCiphertextAdopter(roots, fs)

        val first = adopter.adopt(request(source, bytes))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
            (first as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertFalse(source.exists())
        assertArrayEquals(bytes, destination(ownerA).readBytes())

        fs.failDirectoryForce = false
        val retry = adopter.adopt(request(source, bytes))
        assertTrue(retry is IncomingRecognitionCiphertextAdoptionResult.Adopted)
        assertFalse(source.exists())
    }

    @Test
    fun sourceUnlinkFailureLeavesExactFinalForRetry() = runBlocking {
        val bytes = "unlink-failure".toByteArray()
        val source = source(ownerA, "unlink.tmp", bytes)
        val final = destination(ownerA).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }
        val fs = RecordingFileSystem().apply { failDelete = true }
        val adopter = IncomingRecognitionCiphertextAdopter(roots, fs)

        val first = adopter.adopt(request(source, bytes))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
            (first as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertTrue(source.exists())
        assertArrayEquals(bytes, final.readBytes())

        fs.failDelete = false
        val retry = adopter.adopt(request(source, bytes))
        assertTrue(retry is IncomingRecognitionCiphertextAdoptionResult.Adopted)
        assertFalse(source.exists())
        assertArrayEquals(bytes, destination(ownerA).readBytes())
    }

    @Test
    fun replayAfterPostUnlinkSourceForceFailureClosesMissingSourceDurabilityGap() = runBlocking {
        val bytes = "replay-source-force".toByteArray()
        val source = source(ownerA, "replay-source-force.tmp", bytes)
        val fs = RecordingFileSystem().apply { failSourceParentForce = true }
        val adopter = IncomingRecognitionCiphertextAdopter(roots, fs)
        val destinationParent = destination(ownerA).parentFile!!.toPath()
        val sourceParent = source.parentFile!!.toPath()

        val first = adopter.adopt(request(source, bytes))

        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
            (first as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertFalse(source.exists())
        assertArrayEquals(bytes, destination(ownerA).readBytes())

        fs.failSourceParentForce = false
        val beforeReplay = fs.directoryForcePaths.size
        val replay = adopter.adopt(request(source, bytes))

        assertTrue(replay is IncomingRecognitionCiphertextAdoptionResult.Adopted)
        assertEquals(
            listOf(destinationParent, sourceParent, destinationParent, sourceParent),
            fs.directoryForcePaths,
        )
        assertEquals(emptyList<Path>(), fs.deletedPaths)
        assertTrue(fs.directoryForcePaths.size > beforeReplay)
        assertArrayEquals(bytes, destination(ownerA).readBytes())
    }

    @Test
    fun directoryForcesUseDestinationParentBeforeUnlinkAndSourceParentAfterUnlink() = runBlocking {
        val bytes = "directory-order".toByteArray()
        val source = source(ownerA, "nested/recognition.tmp", bytes)
        val fs = RecordingFileSystem()
        destination(ownerA).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }
        val destinationParent = destination(ownerA).parentFile!!.toPath()
        val sourceParent = source.parentFile!!.toPath()

        val result = IncomingRecognitionCiphertextAdopter(roots, fs).adopt(request(source, bytes))

        assertTrue(result is IncomingRecognitionCiphertextAdoptionResult.Adopted)
        assertTrue(destinationParent != sourceParent)
        assertEquals(
            listOf(destinationParent, sourceParent),
            fs.directoryForcePaths,
        )
        assertEquals(listOf(source.toPath()), fs.deletedPaths)
        assertTrue(fs.directoryForceEvents.indexOf("delete") > fs.directoryForceEvents.indexOf("destination"))
        assertTrue(fs.directoryForceEvents.indexOf("source") > fs.directoryForceEvents.indexOf("delete"))
    }

    @Test
    fun mkdirAndReadFailuresAreRetryableWithoutDeletingSource() = runBlocking {
        val bytes = "local-failure".toByteArray()
        val mkdirSource = source(ownerA, "mkdir.tmp", bytes)
        val mkdirFs = RecordingFileSystem().apply { failMkdir = true }
        val mkdirResult = IncomingRecognitionCiphertextAdopter(roots, mkdirFs)
            .adopt(request(mkdirSource, bytes))
        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
            (mkdirResult as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertTrue(mkdirSource.exists())

        val readSource = source(ownerA, "read.tmp", bytes)
        val readFs = RecordingFileSystem().apply { failRead = true }
        val readResult = IncomingRecognitionCiphertextAdopter(roots, readFs)
            .adopt(request(readSource, bytes))
        assertEquals(
            IncomingRecognitionCiphertextAdoptionFailure.LOCAL_STORAGE,
            (readResult as IncomingRecognitionCiphertextAdoptionResult.Failure).reason,
        )
        assertTrue(readSource.exists())
    }

    @Test
    fun cancellationPropagatesAndLeavesSourceAndFinalSafe() = runBlocking {
        val bytes = "cancel-me".toByteArray()
        val source = source(ownerA, "cancel.tmp", bytes)
        val fs = BlockingReadFileSystem()
        val job = launch(Dispatchers.Default) {
            IncomingRecognitionCiphertextAdopter(roots, fs).adopt(request(source, bytes))
        }

        assertTrue(fs.started.await(5, SECONDS))
        job.cancel()
        fs.release.countDown()
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(source.exists())
        assertFalse(destination(ownerA).exists())
    }

    private fun request(
        source: File,
        bytes: ByteArray,
        owner: UserId = ownerA,
        expectedSizeBytes: Long = bytes.size.toLong(),
        expectedSha256: ByteArray = sha256(bytes),
    ) = IncomingRecognitionCiphertextAdoptionRequest(
        ownerUserId = owner,
        capsuleId = capsule,
        blobId = blob,
        expectedSizeBytes = expectedSizeBytes,
        expectedSha256 = expectedSha256,
        sourceTempFile = source,
    )

    private fun source(owner: UserId, name: String, bytes: ByteArray): File =
        File(roots.child(owner, AccountScopedFileRoots.ChildRoot.TEMP), name).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }

    private fun destination(owner: UserId): File = File(
        File(
            File(roots.child(owner, AccountScopedFileRoots.ChildRoot.INCOMING_CIPHERTEXT), "capsules"),
            capsule.toRestString(),
        ),
        "blobs/${blob.toRestString()}.ciphertext",
    )

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private open class RecordingFileSystem : IncomingCiphertextFileSystem {
        var failMkdir = false
        var failRead = false
        var unsupportedMove = false
        var throwAfterMove = false
        var destinationAppearanceBytes: ByteArray? = null
        var failFileForce = false
        var unsupportedFileForce = false
        var failDirectoryForce = false
        var failSourceParentForce = false
        var failDelete = false
        var forceFileCalls = 0
        val directoryForcePaths = mutableListOf<Path>()
        val directoryForceEvents = mutableListOf<String>()
        val deletedPaths = mutableListOf<Path>()

        override fun attributes(path: Path): IncomingFileAttributes? = try {
            val attrs = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            IncomingFileAttributes(
                attrs.isSymbolicLink,
                attrs.isRegularFile,
                attrs.isDirectory,
                attrs.size(),
            )
        } catch (_: java.nio.file.NoSuchFileException) {
            null
        }

        override fun makeDirectories(path: Path) {
            if (failMkdir) throw IOException("injected mkdir failure")
            Files.createDirectories(path)
        }

        override fun openRead(path: Path): InputStream {
            if (failRead) throw IOException("injected read failure")
            return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        }

        override fun moveNoReplace(source: Path, destination: Path) {
            if (unsupportedMove) throw UnsupportedOperationException("injected unsupported move")
            destinationAppearanceBytes?.let { bytes ->
                destinationAppearanceBytes = null
                Files.write(
                    destination,
                    bytes,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                )
                throw java.nio.file.FileAlreadyExistsException(destination.toString())
            }
            Files.move(source, destination)
            if (throwAfterMove) {
                throwAfterMove = false
                throw IOException("injected ambiguous move failure")
            }
        }

        override fun deleteIfExists(path: Path): Boolean {
            if (failDelete) throw IOException("injected unlink failure")
            deletedPaths.add(path)
            directoryForceEvents.add("delete")
            return Files.deleteIfExists(path)
        }

        override fun forceFile(path: Path) {
            forceFileCalls++
            if (failFileForce) throw IOException("injected file force failure")
            if (unsupportedFileForce) throw UnsupportedOperationException("injected unsupported file force")
            java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
        }

        override fun forceDirectory(path: Path) {
            directoryForcePaths.add(path)
            directoryForceEvents.add(if (path.toString().contains("incoming-ciphertext")) {
                "destination"
            } else {
                "source"
            })
            if (failDirectoryForce) throw IOException("injected directory force failure")
            if (failSourceParentForce && !path.toString().contains("incoming-ciphertext")) {
                throw IOException("injected source-parent force failure")
            }
            java.nio.channels.FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private class BlockingReadFileSystem : RecordingFileSystem() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun openRead(path: Path): InputStream = object : InputStream() {
            override fun read(): Int = read(ByteArray(1), 0, 1)

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                started.countDown()
                release.await()
                return -1
            }
        }
    }
}
