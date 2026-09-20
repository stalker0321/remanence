package dev.hryshyn.remanence.capture

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Bounded resource-contract check for the shared capture surface: the capture
 * string key set is identical across EN, RU and UK, every value is non-blank,
 * and every `R.string.hold_*` actually referenced by the capture sources
 * exists in the default locale. It reads the real `hold_strings.xml` files, so
 * a dropped translation or a stale reference fails here without pinning copy
 * text.
 */
class HoldCaptureStringsParityTest {

    @Test
    fun captureKeySetsMatchAcrossLocalesAndCoverReferencedStrings() {
        val en = readStrings(localeFile("values"))
        val ru = readStrings(localeFile("values-ru"))
        val uk = readStrings(localeFile("values-uk"))

        val enCapture = en.filterKeys { it.startsWith(CAPTURE_PREFIX) }
        val ruCapture = ru.filterKeys { it.startsWith(CAPTURE_PREFIX) }
        val ukCapture = uk.filterKeys { it.startsWith(CAPTURE_PREFIX) }

        assertFalse("no $CAPTURE_PREFIX strings found in default locale", enCapture.isEmpty())
        assertEquals("RU capture key set differs from EN", enCapture.keys, ruCapture.keys)
        assertEquals("UK capture key set differs from EN", enCapture.keys, ukCapture.keys)

        for ((locale, map) in listOf("EN" to en, "RU" to ru, "UK" to uk)) {
            for ((key, value) in map) {
                if (key.startsWith(CAPTURE_PREFIX) || key in referencedKeys()) {
                    assertTrue("$locale $key is blank", value.isNotBlank())
                }
            }
        }

        for (key in referencedKeys()) {
            assertTrue("referenced $key is missing from default locale", en.containsKey(key))
        }
    }

    private fun localeFile(locale: String): File =
        moduleFile("src/main/res/$locale/hold_strings.xml")

    private fun referencedKeys(): Set<String> {
        val sources = listOf(
            "src/main/kotlin/dev/hryshyn/remanence/capture/CaptureAttemptSurface.kt",
            "src/main/kotlin/dev/hryshyn/remanence/capture/QualityFailureScreen.kt",
        )
        return sources.asSequence()
            .map { moduleFile(it).readText() }
            .flatMap { STRING_REFERENCE.findAll(it).asSequence().map { match -> match.groupValues[1] } }
            .toSet()
    }

    private fun readStrings(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("string")
        val values = LinkedHashMap<String, String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            val name = element.getAttribute("name")
            assertFalse("duplicate string $name in ${file.name}", values.containsKey(name))
            values[name] = element.textContent
        }
        return values
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
        error("could not locate $relative from ${File("").absolutePath}")
    }

    private companion object {
        const val CAPTURE_PREFIX = "hold_capture_"
        val STRING_REFERENCE = Regex("""R\.string\.(hold_[a-z_]+)""")
    }
}
