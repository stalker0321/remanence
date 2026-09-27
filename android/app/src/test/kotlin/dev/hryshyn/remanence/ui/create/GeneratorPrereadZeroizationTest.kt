package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.create.GeneratorSourceBinding
import dev.hryshyn.remanence.create.PhotoSource
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class GeneratorPrereadZeroizationTest {

    private fun source(seed: Int) = PhotoSource {
        ByteArrayInputStream(ByteArray(32) { (seed + it).toByte() })
    }

    @Test
    fun partialDecodeFailureWipesCompletedAndCurrentBuffers() = runTest {
        val owned = mutableListOf<ByteArray>()
        val owner = GeneratorPrereadOwner { owned += it }
        var decodeCount = 0
        val result = prereadGeneratorOriginals(
            photoIds = listOf("one", "two", "three"),
            openPhotoSource = { id -> source(id.length) },
            owner = owner,
            decoder = {
                decodeCount += 1
                if (decodeCount == 2) error("decode failed")
                GeneratorSourceBinding.UprightPhoto(100, 80)
            },
            limitBytes = 64,
        )

        assertFalse(result)
        assertTrue("scratch and exact buffers must all be observed", owned.size == 4)
        assertTrue(owned.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun cancellationWipesCompletedAndCurrentBuffersBeforePropagation() = runTest {
        val owned = mutableListOf<ByteArray>()
        val owner = GeneratorPrereadOwner { owned += it }
        var decodeCount = 0
        try {
            prereadGeneratorOriginals(
                photoIds = listOf("one", "two", "three"),
                openPhotoSource = { id -> source(id.length) },
                owner = owner,
                decoder = {
                    decodeCount += 1
                    if (decodeCount == 2) throw CancellationException("cancelled")
                    GeneratorSourceBinding.UprightPhoto(100, 80)
                },
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
        val owner = GeneratorPrereadOwner { owned += it }
        val result = prereadGeneratorOriginals(
            photoIds = listOf("one", "two"),
            owner = owner,
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
            limitBytes = 64,
        )

        assertFalse(result)
        assertTrue("completed arrays plus in-progress scratch must be observed", owned.size == 3)
        assertTrue(owned.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun cancellationAtSuccessfulDispatcherHandoffWipesCallerOwnedBuffers() {
        val caller = ManualDispatcher()
        val io = ManualDispatcher()
        val owned = mutableListOf<ByteArray>()
        val owner = GeneratorPrereadOwner { owned += it }
        val job = CoroutineScope(Job() + caller).launch {
            try {
                val available = withContext(io) {
                    prereadGeneratorOriginals(
                        photoIds = listOf("one"),
                        openPhotoSource = { source(1) },
                        owner = owner,
                        decoder = { GeneratorSourceBinding.UprightPhoto(100, 80) },
                        limitBytes = 64,
                    )
                }
                assertTrue(available)
                fail("cancelled handoff must not resume successful consumption")
            } finally {
                owner.wipe()
            }
        }

        assertTrue(caller.runNext()) // enter withContext and queue IO work
        assertTrue(io.runNext()) // helper succeeds and queues caller resumption
        assertTrue(owned.any { bytes -> bytes.any { it != 0.toByte() } })
        job.cancel()
        assertTrue(caller.runNext()) // prompt cancellation runs caller finally

        assertTrue(job.isCancelled)
        assertTrue(owned.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runNext(): Boolean {
            val next = queued.removeFirstOrNull() ?: return false
            next.run()
            return true
        }
    }
}
