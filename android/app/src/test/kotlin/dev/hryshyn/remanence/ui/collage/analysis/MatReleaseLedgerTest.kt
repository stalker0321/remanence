package dev.hryshyn.remanence.ui.collage.analysis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Native-header ownership policy: the ledger the OpenCV runtime uses to
 * register every Mat source (including the submat ROI) releases each token
 * exactly once, in reverse registration order, and never double-releases. Plus
 * a source pin that the runtime registers the ROI and releases every reshape
 * header in `finally`.
 */
class MatReleaseLedgerTest {

    @Test
    fun releasesRegisteredTokensOnceInReverseOrder() {
        val ledger = MatReleaseLedger()
        val released = mutableListOf<Any>()
        val a = Any()
        val b = Any()
        ledger.register(a)
        ledger.register(b)

        ledger.releaseAll { released += it }

        assertEquals(listOf(b, a), released)
        assertEquals(2, ledger.releasedCount)
        assertEquals(0, ledger.doubleReleaseCount)
        assertEquals(0, ledger.liveCount)
    }

    @Test
    fun duplicateRegistrationIsCountedAsDoubleRelease() {
        val ledger = MatReleaseLedger()
        val token = Any()
        ledger.register(token)
        ledger.register(token)

        ledger.releaseAll { }

        assertEquals(1, ledger.releasedCount)
        assertEquals(1, ledger.doubleReleaseCount)
    }

    @Test
    fun releaseAllIsIdempotent() {
        val ledger = MatReleaseLedger()
        ledger.register(Any())
        ledger.releaseAll { }
        ledger.releaseAll { }
        assertEquals(1, ledger.releasedCount)
        assertEquals(0, ledger.doubleReleaseCount)
    }

    @Test
    fun openCvRuntimeRegistersRoiAndReleasesReshapeHeaders() {
        val source = moduleFile(
            "src/main/kotlin/dev/hryshyn/remanence/ui/collage/analysis/OpenCvNanoDetRuntime.kt",
        ).readText().replace(Regex("\\s+"), " ")

        assertTrue(
            "submat ROI must be registered with the Mat owner",
            source.contains("owner.own( canvas.submat("),
        )
        assertTrue(
            "each reshape header must be released in finally after the bounded copy",
            source.contains("val flat = mat.reshape(1, 1) try { flat.get(0, 0, values) } finally { flat.release() }"),
        )
        assertTrue("runtime must use the release ledger", source.contains("MatReleaseLedger"))
    }

    private fun moduleFile(relative: String): File {
        val prefixes = listOf("", "app/", "android/app/")
        var directory: File? = File("").absoluteFile
        repeat(6) {
            val base = directory
            if (base != null) {
                for (prefix in prefixes) {
                    val candidate = File(base, prefix + relative)
                    if (candidate.exists()) return candidate
                }
            }
            directory = directory?.parentFile
        }
        return File(relative)
    }
}
