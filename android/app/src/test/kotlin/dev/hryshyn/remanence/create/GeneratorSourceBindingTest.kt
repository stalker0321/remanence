package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * G4-B binding tests: fake PhotoSource/decode/normalize only.
 * No real renderer, filesystem, publish or receive path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorSourceBindingTest {

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class FakePhotoSource(
        private val bytes: ByteArray,
        private val fail: Boolean = false,
        val uriMarker: String = "content://media/fake/1",
        val senderMarker: String = "@mykola",
    ) : PhotoSource {
        val opens = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val current = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        override fun openInputStream(): InputStream {
            opens.incrementAndGet()
            val active = current.incrementAndGet()
            maxConcurrent.updateAndGet { max -> maxOf(max, active) }
            if (fail) {
                current.decrementAndGet()
                throw java.io.IOException("source broken")
            }
            return object : ByteArrayInputStream(bytes) {
                override fun close() {
                    super.close()
                    closes.incrementAndGet()
                    current.decrementAndGet()
                }
            }
        }
    }

    private class FakeDecoder(
        private val width: Int,
        private val height: Int,
    ) : GeneratorSourceBinding.PhotoDecoderPort {
        var calls = 0
        override suspend fun decodeUpright(jpeg: ByteArray): GeneratorSourceBinding.UprightPhoto {
            calls++
            return GeneratorSourceBinding.UprightPhoto(width, height)
        }
    }

    private class FakeNormalizer(
        private val width: Int = 120,
        private val height: Int = 213,
        private val bytes: ByteArray = ByteArray(64) { 9 },
        private val hang: Boolean = false,
        private val fail: Boolean = false,
    ) : PhotoNormalizerPort {
        override suspend fun normalize(inputJpeg: ByteArray): NormalizedPhotoDto {
            if (hang) delay(60_000)
            if (fail) throw IllegalStateException("ladder exhausted")
            return NormalizedPhotoDto(bytes, width, height)
        }
    }

    private fun expected(
        bytes: ByteArray,
        id: String = "p1",
        ordinal: Int = 0,
        w: Int = 100,
        h: Int = 200,
    ) = GeneratorSourceBinding.ExpectedOriginal(id, ordinal, sha(bytes), w, h)

    private fun binder(
        normalizer: PhotoNormalizerPort = FakeNormalizer(),
        decoder: GeneratorSourceBinding.PhotoDecoderPort = FakeDecoder(100, 200),
    ) = GeneratorSourceBinding.SourceBinder(normalizer, decoder)

    @Test
    fun oversizeSourceRejectedAndClosed() = runTest {
        val big = ByteArray(PhotoStagingPipeline.MAX_SOURCE_BYTES + 1)
        val source = FakePhotoSource(big)
        val result = binder().bindOne(source, expected(ByteArray(8), w = 100, h = 200))
        assertTrue(result is GeneratorSourceBinding.BindResult.Rejected)
        assertEquals(1, source.opens.get())
        assertEquals(1, source.closes.get())
    }

    @Test
    fun emptySourceRejected() = runTest {
        val source = FakePhotoSource(ByteArray(0))
        val result = binder().bindOne(source, expected(byteArrayOf(1)))
        assertTrue(result is GeneratorSourceBinding.BindResult.Rejected)
        assertEquals(1, source.closes.get())
    }

    @Test
    fun hashMismatchRejected() = runTest {
        val source = FakePhotoSource(byteArrayOf(1, 2, 3))
        val result = binder().bindOne(source, expected(byteArrayOf(9, 9, 9)))
        assertTrue(result is GeneratorSourceBinding.BindResult.Rejected)
        assertEquals(1, source.closes.get())
    }

    @Test
    fun exifRotationAcceptedAsUpright() = runTest {
        // Stored bytes decode to a rotated frame; the upright 200x100
        // matches the declared G1 dims, so the EXIF path is honored.
        val bytes = byteArrayOf(4, 5, 6)
        val source = FakePhotoSource(bytes)
        val bound = binder(decoder = FakeDecoder(200, 100)).bindOne(source, expected(bytes, w = 200, h = 100))
        assertTrue(bound is GeneratorSourceBinding.BindResult.Bound)
        val value = bound as GeneratorSourceBinding.BindResult.Bound
        assertEquals(200, bound.bound.uprightWidthPx)
        assertEquals(100, bound.bound.uprightHeightPx)
        assertEquals(sha(bytes), bound.bound.originalHash)
        assertEquals(1, source.closes.get())
    }

    @Test
    fun dimensionMismatchRejected() = runTest {
        val bytes = byteArrayOf(4, 5, 6)
        val source = FakePhotoSource(bytes)
        val result = binder(decoder = FakeDecoder(100, 200)).bindOne(source, expected(bytes, w = 200, h = 100))
        assertTrue(result is GeneratorSourceBinding.BindResult.Rejected)
        assertEquals(1, source.closes.get())
    }

    @Test
    fun swappedSourcesBothRejected() = runTest {
        val bytesA = byteArrayOf(10)
        val bytesB = byteArrayOf(20)
        val sourceA = FakePhotoSource(bytesA)
        val sourceB = FakePhotoSource(bytesB)
        val results = binder().bindAll(
            listOf(
                sourceA to expected(bytesB, id = "p1", ordinal = 0),
                sourceB to expected(bytesA, id = "p2", ordinal = 1),
            ),
        )
        assertEquals(2, results.size)
        assertTrue(results.all { it is GeneratorSourceBinding.BindResult.Rejected })
        assertEquals(1, sourceA.closes.get())
        assertEquals(1, sourceB.closes.get())
    }

    @Test
    fun normalizationExhaustionRejected() = runTest {
        val bytes = byteArrayOf(7, 8)
        val source = FakePhotoSource(bytes)
        val result = binder(normalizer = FakeNormalizer(fail = true)).bindOne(source, expected(bytes))
        assertTrue(result is GeneratorSourceBinding.BindResult.Rejected)
        assertEquals(1, source.closes.get())
    }

    @Test
    fun derivedOverBudgetRejected() = runTest {
        val bytes = byteArrayOf(7, 8)
        val source = FakePhotoSource(bytes)
        val huge = ByteArray(dev.hryshyn.remanence.core.crypto.PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES + 1)
        val result = binder(normalizer = FakeNormalizer(bytes = huge)).bindOne(source, expected(bytes))
        assertTrue(result is GeneratorSourceBinding.BindResult.Rejected)
        assertEquals(1, source.closes.get())
    }

    @Test
    fun cancellationPropagatesAndClosesSource() = runTest {
        val bytes = byteArrayOf(7, 8)
        val source = FakePhotoSource(bytes)
        val job = async {
            binder(normalizer = FakeNormalizer(hang = true)).bindOne(source, expected(bytes))
        }
        runCurrent()
        job.cancel()
        try {
            job.await()
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
        }
        assertEquals(1, source.closes.get())
    }

    @Test
    fun sourcesOpenOneAtATimeAndCloseExactlyOnce() = runTest {
        val blobs = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val sources = blobs.map { FakePhotoSource(it) }
        val pairs = blobs.mapIndexed { index, bytes ->
            sources[index] to expected(bytes, id = "p$index", ordinal = index)
        }
        val results = binder().bindAll(pairs)
        assertEquals(3, results.count { it is GeneratorSourceBinding.BindResult.Bound })
        for (source in sources) {
            assertEquals(1, source.opens.get())
            assertEquals(1, source.closes.get())
            assertEquals(1, source.maxConcurrent.get())
        }
    }

    @Test
    fun identityIsolationFromUriAndSenderMarkers() = runTest {
        val blobs = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val sources = blobs.map { FakePhotoSource(it) }
        val bound = binder().bindAll(
            blobs.mapIndexed { index, bytes ->
                sources[index] to expected(bytes, id = "p$index", ordinal = index)
            },
        ).map {
            assertTrue(it is GeneratorSourceBinding.BindResult.Bound)
            (it as GeneratorSourceBinding.BindResult.Bound).bound
        }
        val refs = bound.map { binder().toPhotoRef(it) }
        val viaBinder = GeneratorExpression.GeneratorInput("owner-1", 7L, refs, "n", null)
        val direct = GeneratorExpression.GeneratorInput(
            "owner-1", 7L,
            blobs.mapIndexed { index, bytes ->
                GeneratorExpression.PhotoRef("p$index", index, 100, 200, sha(bytes))
            },
            "n", null,
        )
        assertEquals(
            GeneratorExpression.canonicalHash(direct),
            GeneratorExpression.canonicalHash(viaBinder),
        )
        val raw = String(GeneratorExpression.canonicalBytes(viaBinder), Charsets.UTF_8)
        assertTrue(!raw.contains("content://media/fake/1"))
        assertTrue(!raw.contains("@mykola"))
    }

    @Test
    fun contractVersionsFailClosed() {
        GeneratorSourceBinding.requireSupportedContractVersion(1)
        try {
            GeneratorSourceBinding.requireSupportedContractVersion(0)
            fail("version 0 must fail closed")
        } catch (e: IllegalArgumentException) {
        }
        try {
            GeneratorSourceBinding.requireSupportedContractVersion(2)
            fail("version 2 must fail closed")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun uprightAndNormalizedStayDistinct() = runTest {
        val bytes = byteArrayOf(11, 12)
        val source = FakePhotoSource(bytes)
        val bound = binder(
            decoder = FakeDecoder(300, 400),
            normalizer = FakeNormalizer(width = 120, height = 213),
        ).bindOne(source, expected(bytes, w = 300, h = 400))
        assertTrue(bound is GeneratorSourceBinding.BindResult.Bound)
        val value = (bound as GeneratorSourceBinding.BindResult.Bound).bound
        assertEquals(300, value.uprightWidthPx)
        assertEquals(400, value.uprightHeightPx)
        assertEquals(sha(bytes), value.originalHash)
        assertEquals(120, value.normalizedWidthPx)
        assertEquals(213, value.normalizedHeightPx)
        assertEquals(64, value.normalizedBytes.size)
    }
}
