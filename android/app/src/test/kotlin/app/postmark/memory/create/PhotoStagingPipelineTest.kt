package app.postmark.memory.create

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

    private fun sources(n: Int): List<ByteArray> =
        (1..n).map { "jpeg-source-$it".toByteArray() }

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
    fun failureOnLaterPhotoRemovesAllArtifactsOfThisRun() {
        val dir = Files.createTempDirectory("staging-fail")
        val pipeline = PhotoStagingPipeline(
            stagingDirectory = dir.toFile(),
            normalizer = { input ->
                if (input.contentEquals("jpeg-source-4".toByteArray())) {
                    throw IllegalStateException("normalizer exploded")
                }
                NormalizedPhotoDto(input.copyOf(), 800, 600)
            },
        )
        try {
            pipeline.stageAll(sources(5))
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("normalizer exploded", expected.message)
        }
        assertEquals(0, dir.toFile().listFiles()?.size ?: 0)
    }

    @Test
    fun overflowInputRejectedBeforeAnyWrite() {
        val (pipeline, dir) = newPipeline()
        try {
            pipeline.stageAll(sources(6))
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("got 6"))
        }
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
