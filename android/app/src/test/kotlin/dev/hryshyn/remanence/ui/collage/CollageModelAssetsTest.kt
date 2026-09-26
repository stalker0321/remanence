package dev.hryshyn.remanence.ui.collage

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5-A1 inventory: the packaged collage CV assets must be byte-exact copies of
 * the authoritative V5 freeze (`remanence_capsule_engine_v5_freeze_2026-09-25`
 * `runtime/models/`). Sizes and SHA-256 are pinned from the freeze
 * `CHECKSUMS.sha256`.
 *
 * `nanodet.py` is a Python reference decoder, not an Android runtime input, so
 * it is deliberately NOT packaged; this test asserts it stays out of the APK.
 */
class CollageModelAssetsTest {

    private val expected = mapOf(
        "nanodet.onnx" to (3_800_954L to "4b82da9944b88577175ee23a459dce2e26e6e4be573def65b1055dc2d9720186"),
        "yunet.onnx" to (232_589L to "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"),
        "NANODET-LICENSE" to (11_358L to "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
        "NANODET-README.md" to (5_927L to "718e15301612de9735d478c573bf876aaa3551912b2c693bd9275a9d4a4d85d0"),
        "YUNET-LICENSE" to (1_085L to "c83b8120c50ccbd4c4f96edf53141bdd566ebb8f8e9227e415326aa1b1aba958"),
    )

    private fun sha256Hex(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

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

    private fun asset(name: String): File = moduleFile("src/main/assets/collage/$name")

    @Test
    fun packagedAssetsMatchTheFreezeChecksumsAndSizes() {
        for ((name, pin) in expected) {
            val file = asset(name)
            assertTrue("missing packaged asset $name", file.isFile)
            assertEquals("size mismatch for $name", pin.first, file.length())
            assertEquals("sha256 mismatch for $name", pin.second, sha256Hex(file))
        }
    }

    @Test
    fun pythonReferenceDecoderIsNotPackaged() {
        assertFalse(
            "nanodet.py is reference-only and must not ship in the APK",
            asset("nanodet.py").exists(),
        )
        val assetsDir = moduleFile("src/main/assets/collage")
        val strayPython = assetsDir.listFiles()?.filter { it.name.endsWith(".py") }.orEmpty()
        assertTrue("no .py files may be packaged: $strayPython", strayPython.isEmpty())
    }

    @Test
    fun licensesArePresentAndNonEmpty() {
        for (name in listOf("NANODET-LICENSE", "YUNET-LICENSE", "NANODET-README.md")) {
            val file = asset(name)
            assertTrue("missing license/provenance $name", file.isFile)
            assertTrue("empty license/provenance $name", file.length() > 0L)
        }
    }
}
