package dev.hryshyn.remanence.create

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStagingPipelineTest {

    private fun newPipeline(): Pair<PhotoStagingPipeline, java.nio.file.Path> {
        val dir = Files.createTempDirectory("staging")
        val pipeline = PhotoStagingPipeline(
            stagingDirectory = dir.toFile(),
            normalizer = { input -> NormalizedPhotoDto(input.copyOf(), 800, 600) },
        )
        return pipeline to dir
    }

    private fun sources(n: Int): List<PhotoSource> =
        (1..n).map { index -> PhotoSource { ByteArrayInputStream("jpeg-source-$index".toByteArray()) } }

    /** Counts concurrent opens and records open/close order to prove single-source streaming. */
    private class CountingSources(count: Int) {
        private val size = count
        val events = mutableListOf<String>()
        var currentlyOpen = 0
            private set
        var maxConcurrentOpen = 0
            private set
        var totalOpens = 0
            private set
        var totalCloses = 0
            private set

        fun list(): List<PhotoSource> =
            (1..size).map { index ->
                PhotoSource {
                    currentlyOpen += 1
                    maxConcurrentOpen = maxOf(maxConcurrentOpen, currentlyOpen)
                    totalOpens += 1
                    events += "open$index"
                    object : InputStream() {
                        private val delegate = ByteArrayInputStream("jpeg-source-$index".toByteArray())
                        override fun read(): Int = delegate.read()
                        override fun close() {
                            currentlyOpen -= 1
                            totalCloses += 1
                            events += "close$index"
                        }
                    }
                }
            }
    }

    @Test
    fun threePhotosStageSuccessfullyInOrder() {
        val (pipeline, dir) = newPipeline()
        val staged = kotlinx.coroutines.runBlocking { pipeline.stageAll(sources(3)) }
        assertEquals(3, staged.size)
        staged.forEachIndexed { index, photo ->
            assertEquals("photo-%02d.jpg".format(index), photo.file.name)
            assertTrue(photo.file.exists())
            assertEquals("jpeg-source-${index + 1}", photo.file.readText())
            assertEquals(800, photo.width)
        }
        assertEquals(3, dir.toFile().listFiles()?.size)
    }

    @Test
    fun fivePhotosStageSuccessfully() {
        val (pipeline, dir) = newPipeline()
        val staged = kotlinx.coroutines.runBlocking { pipeline.stageAll(sources(5)) }
        assertEquals(5, staged.size)
        assertEquals(5, dir.toFile().listFiles()?.size)
    }

    @Test
    fun atMostOneSourceIsOpenAtAnyMomentAndEachClosesBeforeNextOpens() {
        val (pipeline, _) = newPipeline()
        val counting = CountingSources(4)

        kotlinx.coroutines.runBlocking { pipeline.stageAll(counting.list()) }

        assertEquals("sources must be streamed one at a time", 1, counting.maxConcurrentOpen)
        assertEquals("every opened source must be closed", counting.totalOpens, counting.totalCloses)
        assertEquals(
            listOf("open1", "close1", "open2", "close2", "open3", "close3", "open4", "close4"),
            counting.events,
        )
    }

    @Test
    fun failureOnLaterSourceRemovesAllArtifactsAndClosesOpenedStreams() {
        val dir = Files.createTempDirectory("staging-fail")
        val explodingIndex = 3
        val pipeline = PhotoStagingPipeline(
            stagingDirectory = dir.toFile(),
            normalizer = { input ->
                if (input.contentEquals("jpeg-source-4".toByteArray())) {
                    throw IllegalStateException("normalizer exploded")
                }
                NormalizedPhotoDto(input.copyOf(), 800, 600)
            },
        )
        val counting = CountingSources(5)
        try {
            kotlinx.coroutines.runBlocking { pipeline.stageAll(counting.list()) }
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("normalizer exploded", expected.message)
        }
        assertEquals(0, dir.toFile().listFiles()?.size ?: 0)
        assertEquals("every opened source must be closed on failure", counting.totalOpens, counting.totalCloses)
        assertEquals(explodingIndex + 1, counting.totalOpens)
    }

    @Test
    fun sourceOpenFailureRemovesPriorArtifacts() {
        val dir = Files.createTempDirectory("staging-open-fail")
        val pipeline = PhotoStagingPipeline(
            stagingDirectory = dir.toFile(),
            normalizer = { input -> NormalizedPhotoDto(input.copyOf(), 800, 600) },
        )
        val failingAt = 2
        val sources = sources(3).mapIndexed { index, source ->
            if (index == failingAt) {
                PhotoSource { throw SecurityException("picker source unavailable") }
            } else {
                source
            }
        }
        try {
            kotlinx.coroutines.runBlocking { pipeline.stageAll(sources) }
            throw AssertionError("expected failure")
        } catch (expected: SecurityException) {
            assertEquals("picker source unavailable", expected.message)
        }
        assertEquals(0, dir.toFile().listFiles()?.size ?: 0)
    }

    @Test
    fun overflowInputRejectedBeforeAnyOpenOrWrite() {
        val (pipeline, dir) = newPipeline()
        val counting = CountingSources(6)
        try {
            kotlinx.coroutines.runBlocking { pipeline.stageAll(counting.list()) }
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("got 6"))
        }
        assertEquals("rejection must not open any source", 0, counting.totalOpens)
        assertEquals(0, dir.toFile().listFiles()?.size ?: 0)
    }

    @Test
    fun undersizedInputRejected() {
        val (pipeline, _) = newPipeline()
        try {
            kotlinx.coroutines.runBlocking { pipeline.stageAll(sources(2)) }
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("got 2"))
        }
    }

    @Test
    fun clearStagedRemovesArtifacts() {
        val (pipeline, dir) = newPipeline()
        kotlinx.coroutines.runBlocking { pipeline.stageAll(sources(4)) }
        pipeline.clearStaged()
        assertEquals(0, dir.toFile().listFiles()?.size ?: 0)
    }

    @Test
    fun sourceOneByteUnderPlaintextBudgetStagesFully() {
        val (pipeline, dir) = newPipeline()
        val payload = ByteArray(dev.hryshyn.remanence.core.crypto.PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES - 1) { it.toByte() }
        val staged = kotlinx.coroutines.runBlocking {
            pipeline.stageAll(listOf(PhotoSource { java.io.ByteArrayInputStream(payload) }) + sources(2))
        }
        assertEquals(payload.size.toLong(), staged[0].file.length())
        assertEquals(3, dir.toFile().listFiles()?.size)
    }

    @Test
    fun sourceExactlyAtPlaintextBudgetStagesFully() {
        val (pipeline, _) = newPipeline()
        val payload = ByteArray(dev.hryshyn.remanence.core.crypto.PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES) { it.toByte() }
        val staged = kotlinx.coroutines.runBlocking {
            pipeline.stageAll(listOf(PhotoSource { java.io.ByteArrayInputStream(payload) }) + sources(2))
        }
        assertEquals(payload.size.toLong(), staged[0].file.length())
    }

    @Test
    fun sourceOneByteOverPlaintextBudgetRejectedWithCleanupAndClosedStreams() {
        val dir = Files.createTempDirectory("staging-over-budget").toFile()
        val counting = CountingSources(3)
        val pipeline = PhotoStagingPipeline(
            stagingDirectory = dir,
            normalizer = { input -> NormalizedPhotoDto(input.copyOf(), 800, 600) },
        )
        // Replace the middle source with an over-budget stream that tracks its own close.
        var rejectedStreamClosed = false
        val overBudgetSources = counting.list().mapIndexed { index, source ->
            if (index == 1) {
                PhotoSource {
                    object : InputStream() {
                        private val delegate =
                            java.io.ByteArrayInputStream(ByteArray(dev.hryshyn.remanence.core.crypto.PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES + 1))
                        override fun read(): Int = delegate.read()
                        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
                        override fun close() {
                            rejectedStreamClosed = true
                            delegate.close()
                        }
                    }
                }
            } else {
                source
            }
        }
        try {
            kotlinx.coroutines.runBlocking { pipeline.stageAll(overBudgetSources) }
            throw AssertionError("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("plaintext budget"))
        }
        assertEquals(0, dir.listFiles()?.size ?: 0)
        assertTrue("rejection must happen while reading the second photo", counting.totalOpens == 1)
        assertTrue("the rejected source must be closed", rejectedStreamClosed)
    }

    @Test
    fun endlessHostileSourceIsCutOffAtBudgetInsteadOfExhaustingMemory() {
        val dir = Files.createTempDirectory("staging-endless").toFile()
        var closed = false
        val pipeline = PhotoStagingPipeline(
            stagingDirectory = dir,
            normalizer = { input -> NormalizedPhotoDto(input.copyOf(), 800, 600) },
        )
        val endless: InputStream = object : InputStream() {
            private val chunk = ByteArray(64 * 1024)
            override fun read(): Int = 0x41
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                System.arraycopy(chunk, 0, b, off, len)
                return len
            }
            override fun close() {
                closed = true
            }
        }
        try {
            kotlinx.coroutines.runBlocking {
                pipeline.stageAll(listOf(PhotoSource { endless }) + sources(2))
            }
            throw AssertionError("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("plaintext budget"))
        }
        assertTrue("cut-off source must be closed", closed)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun hostileStreamLyingAboutAvailableIsStillReadByContent() {
        val (pipeline, _) = newPipeline()
        val liar: InputStream = object : InputStream() {
            private val delegate = ByteArrayInputStream("jpeg-liar".toByteArray())
            override fun read(): Int = delegate.read()
            override fun available(): Int = Int.MAX_VALUE
        }
        val staged = kotlinx.coroutines.runBlocking {
            pipeline.stageAll(listOf(PhotoSource { liar }) + sources(2))
        }
        assertEquals("jpeg-liar", staged[0].file.readText())
    }

    @Test
    fun refusesToStageOverPreExistingArtifactsAndLeavesThemUntouched() {
        val dir = Files.createTempDirectory("staging-pre-existing").toFile()
        val keptTarget = java.io.File(dir, "photo-00.jpg")
        val keptTmp = java.io.File(dir, "photo-01.jpg.tmp")
        keptTarget.writeText("pre-existing target")
        keptTmp.writeText("stale plaintext tmp")
        val (pipeline, _) = newPipelineAt(dir)
        val counting = CountingSources(3)

        try {
            kotlinx.coroutines.runBlocking { pipeline.stageAll(counting.list()) }
            throw AssertionError("expected refusal")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("must be empty before staging"))
        }
        assertEquals(0, counting.totalOpens)
        assertEquals("pre-existing target", keptTarget.readText())
        assertEquals("stale plaintext tmp", keptTmp.readText())
    }

    @Test
    fun failedAtomicWriteNeverLeavesThePlaintextTmpBehind() {
        val (pipeline, dir) = newPipeline()
        // A directory occupying the target path makes the rename fail after the
        // tmp was fully written, which is exactly the finally-deletion case.
        val conflictingTarget = java.io.File(dir.toFile(), "photo-00.jpg")
        assertTrue(conflictingTarget.mkdirs())

        try {
            pipeline.atomicWrite(conflictingTarget, "secret-plaintext".toByteArray())
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("could not persist photo-00.jpg", expected.message)
        }
        assertTrue(
            "the plaintext .tmp must be deleted in finally",
            !java.io.File(dir.toFile(), "photo-00.jpg.tmp").exists(),
        )
        assertTrue(conflictingTarget.isDirectory && conflictingTarget.listFiles()?.isEmpty() == true)
    }

    private fun newPipelineAt(dir: java.io.File): Pair<PhotoStagingPipeline, java.nio.file.Path> {
        val pipeline = PhotoStagingPipeline(
            stagingDirectory = dir,
            normalizer = { input -> NormalizedPhotoDto(input.copyOf(), 800, 600) },
        )
        return pipeline to dir.toPath()
    }
}
