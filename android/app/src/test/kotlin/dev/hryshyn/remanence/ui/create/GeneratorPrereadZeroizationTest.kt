package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.create.GeneratorSourceBinding
import dev.hryshyn.remanence.create.PhotoSource
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeneratorPrereadZeroizationTest {

    private fun source(seed: Int) = PhotoSource {
        ByteArrayInputStream(ByteArray(32) { (seed + it).toByte() })
    }

    @Test
    fun partialDecodeFailureWipesCompletedAndCurrentBuffers() = runTest {
        val owned = mutableListOf<ByteArray>()
        var decodeCount = 0
        val result = prereadGeneratorOriginals(
            photoIds = listOf("one", "two", "three"),
            openPhotoSource = { id -> source(id.length) },
            decoder = {
                decodeCount += 1
                if (decodeCount == 2) error("decode failed")
                GeneratorSourceBinding.UprightPhoto(100, 80)
            },
            ownedObserver = { owned += it },
            limitBytes = 64,
        )

        assertNull(result)
        assertTrue("scratch and exact buffers must all be observed", owned.size == 4)
        assertTrue(owned.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun cancellationWipesCompletedAndCurrentBuffersBeforePropagation() = runTest {
        val owned = mutableListOf<ByteArray>()
        var decodeCount = 0
        try {
            prereadGeneratorOriginals(
                photoIds = listOf("one", "two", "three"),
                openPhotoSource = { id -> source(id.length) },
                decoder = {
                    decodeCount += 1
                    if (decodeCount == 2) throw CancellationException("cancelled")
                    GeneratorSourceBinding.UprightPhoto(100, 80)
                },
                ownedObserver = { owned += it },
                limitBytes = 64,
            )
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }

        assertTrue("scratch and exact buffers must all be observed", owned.size == 4)
        assertTrue(owned.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun partialReadFailureWipesCompletedAndInProgressBuffers() = runTest {
        val owned = mutableListOf<ByteArray>()
        val result = prereadGeneratorOriginals(
            photoIds = listOf("one", "two"),
            openPhotoSource = { id ->
                if (id == "one") {
                    source(1)
                } else {
                    PhotoSource {
                        object : ByteArrayInputStream(ByteArray(32) { 7 }) {
                            private var reads = 0
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                reads += 1
                                if (reads > 1) throw IOException("read failed")
                                return super.read(buffer, offset, minOf(length, 8))
                            }
                        }
                    }
                }
            },
            decoder = { GeneratorSourceBinding.UprightPhoto(100, 80) },
            ownedObserver = { owned += it },
            limitBytes = 64,
        )

        assertNull(result)
        assertTrue("completed arrays plus in-progress scratch must be observed", owned.size == 3)
        assertTrue(owned.all { bytes -> bytes.all { it == 0.toByte() } })
    }
}
