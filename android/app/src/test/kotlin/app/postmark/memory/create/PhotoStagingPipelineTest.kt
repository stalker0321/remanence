package app.postmark.memory.create

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
        val staged = pipeline.stageAll(sources(3))
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
        val staged = pipeline.stageAll(sources(5))
        assertEquals(5, staged.size)
        assertEquals(5, dir.toFile().listFiles()?.size)
    }

    @Test
    fun atMostOneSourceIsOpenAtAnyMomentAndEachClosesBeforeNextOpens() {
        val (pipeline, _) = newPipeline()
        val counting = CountingSources(4)

        pipeline.stageAll(counting.list())

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
            pipeline.stageAll(counting.list())
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
            pipeline.stageAll(sources)
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
            pipeline.stageAll(counting.list())
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
            pipeline.stageAll(sources(2))
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("got 2"))
        }
    }

    @Test
    fun clearStagedRemovesArtifacts() {
        val (pipeline, dir) = newPipeline()
        pipeline.stageAll(sources(4))
        pipeline.clearStaged()
        assertEquals(0, dir.toFile().listFiles()?.size ?: 0)
    }
}
