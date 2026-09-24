package dev.hryshyn.remanence.create

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Host-side tests for the bounded, fail-closed preview loader. */
class GeneratorPreviewLoaderTest {

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val decoderOk = GeneratorSourceBinding.PhotoDecoderPort {
        GeneratorSourceBinding.UprightPhoto(1080, 1920)
    }
    private val normalizerOk = PhotoNormalizerPort {
        NormalizedPhotoDto(
            jpegBytes = byteArrayOf(9, 8, 7),
            width = 540,
            height = 960,
        )
    }

    private fun loader(
        bytes: ByteArray,
        decoder: GeneratorSourceBinding.PhotoDecoderPort = decoderOk,
        normalizer: PhotoNormalizerPort = normalizerOk,
        maxSourceBytes: Int = PhotoStagingPipeline.MAX_SOURCE_BYTES,
    ) = DefaultGeneratorPreviewLoader(
        openSource = { PhotoSource { ByteArrayInputStream(bytes) } },
        decoder = decoder,
        normalizer = normalizer,
        maxSourceBytes = maxSourceBytes,
    )

    @Test
    fun loadsUprightSourceWithOriginalHashAndPreviewBytes() = runTest {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val loaded = loader(original).load("uri-1")
        requireNotNull(loaded) { "expected a loaded source" }
        assertEquals(sha256Hex(original), loaded.originalHash)
        assertEquals(1080, loaded.uprightWidthPx)
        assertEquals(1920, loaded.uprightHeightPx)
        assertArrayEquals(byteArrayOf(9, 8, 7), loaded.previewJpegBytes)
        assertEquals(540, loaded.previewWidthPx)
        assertEquals(960, loaded.previewHeightPx)
    }

    @Test
    fun overBudgetSourceFailsClosed() = runTest {
        assertNull(loader(ByteArray(9) { 1 }, maxSourceBytes = 8).load("uri-1"))
    }

    @Test
    fun decoderOrNormalizerFailureFailsClosed() = runTest {
        val throwingDecoder = GeneratorSourceBinding.PhotoDecoderPort { throw IllegalStateException("bad") }
        assertNull(loader(byteArrayOf(1), decoder = throwingDecoder).load("uri-1"))

        val throwingNormalizer = PhotoNormalizerPort { throw IllegalStateException("bad") }
        assertNull(loader(byteArrayOf(1), normalizer = throwingNormalizer).load("uri-1"))

        val emptyNormalizer = PhotoNormalizerPort { NormalizedPhotoDto(ByteArray(0), 10, 10) }
        assertNull(loader(byteArrayOf(1), normalizer = emptyNormalizer).load("uri-1"))
    }
}
