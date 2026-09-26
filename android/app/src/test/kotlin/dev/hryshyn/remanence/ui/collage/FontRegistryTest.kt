package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.R
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the bundled collage faces to the exact nine Nimbus/URW OTFs copied from
 * the capsule-engine V5 freeze `runtime/assets/fonts/` (verified against the
 * freeze `CHECKSUMS.sha256`), pins the section 8 face-availability matrix, and
 * pins the shipped `LICENSE.txt`. Faces the freeze does not ship stay
 * fail-closed.
 */
class FontRegistryTest {

    /** `res/font` basename to the freeze SHA-256 of the bundled asset. */
    private val assetChecksums = mapOf(
        "nimbus_sans_regular.otf" to "7c25be4d78155523080ab85b10277150657ff7dabbcad7037bdd536c9b6d0d08",
        "nimbus_sans_bold.otf" to "7f33328e6b4d4cd21b45fa625791928c9407dc702db6780e56b09ca9a3ecaa67",
        "nimbus_roman_regular.otf" to "fd41669443616a7ae890c42e53a58f47fa24a940fd1e367acaff665cf5b6e9d8",
        "nimbus_roman_italic.otf" to "b7c424e6cb79baddfcdcd9a9d87f9fc4fdd06f0e5160066e2b2e47bcd647c7b9",
        "nimbus_roman_bold.otf" to "e0ad81923bc6d85d5426f796217c87963ca1ae3c65e22c87a25b6c6353de299e",
        "nimbus_mono_ps_regular.otf" to "4f225ca8e13acb16f733ce741693105e527d5f7a5443901b9ecc190fca4e149b",
        "nimbus_sans_narrow_bold.otf" to "6d7cec07f5a9035e208532dab35a51312aef4a7f26eb524dd614c9fcd97c3264",
        "urw_gothic_book.otf" to "04318316cee29950805110c9c8949eea189ef5575132881f4d4d7e03e5299903",
        "urw_gothic_demi.otf" to "5b009410cf5231dcb1e45b155c1afedcfc63d82042fd8c414d0dd7705c9fbbae",
    )

    private val licenseChecksum = "1315a62758a603a3e6e87a79b8389563789c59c1740b9db8bc2b20e2e2145458"

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

    @Test
    fun exactlyTheNineRequiredFacesAreBundled() {
        assertEquals(9, FontRegistry.bundledFaces.size)
        assertEquals(
            setOf(
                FontRegistry.Face("StudySans", 400, false),
                FontRegistry.Face("StudySans", 700, false),
                FontRegistry.Face("StudySerif", 400, false),
                FontRegistry.Face("StudySerif", 400, true),
                FontRegistry.Face("StudySerif", 700, false),
                FontRegistry.Face("StudyMono", 400, false),
                FontRegistry.Face("Narrow", 700, false),
                FontRegistry.Face("Gothic", 400, false),
                FontRegistry.Face("Gothic", 700, false),
            ),
            FontRegistry.bundledFaces.map { it.face }.toSet(),
        )
    }

    @Test
    fun faceMappingIsExact() {
        assertEquals(R.font.nimbus_sans_regular, FontRegistry.resourceFor("StudySans", 400, false))
        assertEquals(R.font.nimbus_sans_bold, FontRegistry.resourceFor("StudySans", 700, false))
        assertEquals(R.font.nimbus_roman_regular, FontRegistry.resourceFor("StudySerif", 400, false))
        assertEquals(R.font.nimbus_roman_italic, FontRegistry.resourceFor("StudySerif", 400, true))
        assertEquals(R.font.nimbus_roman_bold, FontRegistry.resourceFor("StudySerif", 700, false))
        assertEquals(R.font.nimbus_mono_ps_regular, FontRegistry.resourceFor("StudyMono", 400, false))
        assertEquals(R.font.nimbus_sans_narrow_bold, FontRegistry.resourceFor("Narrow", 700, false))
        assertEquals(R.font.urw_gothic_book, FontRegistry.resourceFor("Gothic", 400, false))
        assertEquals(R.font.urw_gothic_demi, FontRegistry.resourceFor("Gothic", 700, false))
    }

    @Test
    fun bundledFaceProvenanceNamesTheExactAsset() {
        assertEquals("nimbus_sans_regular.otf", FontRegistry.bundledFaceFor("StudySans", 400, false)?.assetFile)
        assertEquals("nimbus_sans_bold.otf", FontRegistry.bundledFaceFor("StudySans", 700, false)?.assetFile)
        assertEquals("nimbus_roman_regular.otf", FontRegistry.bundledFaceFor("StudySerif", 400, false)?.assetFile)
        assertEquals("nimbus_roman_italic.otf", FontRegistry.bundledFaceFor("StudySerif", 400, true)?.assetFile)
        assertEquals("nimbus_roman_bold.otf", FontRegistry.bundledFaceFor("StudySerif", 700, false)?.assetFile)
        assertEquals("nimbus_mono_ps_regular.otf", FontRegistry.bundledFaceFor("StudyMono", 400, false)?.assetFile)
        assertEquals("nimbus_sans_narrow_bold.otf", FontRegistry.bundledFaceFor("Narrow", 700, false)?.assetFile)
        assertEquals("urw_gothic_book.otf", FontRegistry.bundledFaceFor("Gothic", 400, false)?.assetFile)
        assertEquals("urw_gothic_demi.otf", FontRegistry.bundledFaceFor("Gothic", 700, false)?.assetFile)
    }

    @Test
    fun unbundledFacesFailClosed() {
        val absent = listOf(
            FontRegistry.Face("StudySans", 400, true),
            FontRegistry.Face("StudySans", 700, true),
            FontRegistry.Face("StudySans", 500, false),
            FontRegistry.Face("StudySerif", 700, true),
            FontRegistry.Face("StudySerif", 500, false),
            FontRegistry.Face("StudyMono", 400, true),
            FontRegistry.Face("StudyMono", 700, false),
            FontRegistry.Face("Narrow", 400, false),
            FontRegistry.Face("Narrow", 700, true),
            FontRegistry.Face("Gothic", 400, true),
            FontRegistry.Face("Gothic", 700, true),
            FontRegistry.Face("Gothic", 500, false),
            FontRegistry.Face("Unknown", 400, false),
        )
        for (face in absent) {
            assertNull(
                "expected ${face.family} to fail closed",
                FontRegistry.resourceFor(face.family, face.weight, face.italic),
            )
            assertNull(FontRegistry.bundledFaceFor(face.family, face.weight, face.italic))
        }
    }

    @Test
    fun bundledFontInventoryAndLicenseArePresentAndExact() {
        for ((assetFile, checksum) in assetChecksums) {
            val file = moduleFile("src/main/res/font/$assetFile")
            assertTrue("missing bundled font asset $assetFile", file.isFile)
            assertTrue("empty font asset $assetFile", file.length() > 0L)
            val header = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
            assertEquals("$assetFile is not an OpenType/CFF font", "OTTO", String(header, Charsets.US_ASCII))
            assertEquals("$assetFile does not match the freeze checksum", checksum, sha256Hex(file))
        }
        assertEquals(
            "bundled face inventory must match the nine assets",
            assetChecksums.keys,
            FontRegistry.bundledFaces.map { it.assetFile }.toSet(),
        )

        val license = moduleFile("src/main/assets/fonts/LICENSE.txt")
        assertTrue("missing bundled font license", license.isFile)
        assertEquals("LICENSE.txt does not match the freeze checksum", licenseChecksum, sha256Hex(license))
    }
}
