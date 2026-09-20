package dev.hryshyn.remanence.ui.scan

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Bounded resource-contract check for HOLD-06 scan product copy: the
 * `hold_scan_*`/`hold_chooser_*` key sets are identical across EN, RU and UK,
 * every value is non-blank, and every scan/chooser string referenced by the
 * scan sources exists in the default locale. It reads the real
 * `hold_strings.xml` files, so a dropped translation or stale reference fails
 * here without pinning copy text.
 */
class HoldScanStringsParityTest {

    @Test
    fun scanKeySetsMatchAcrossLocalesAndCoverReferencedStrings() {
        val en = readStrings(localeFile("values"))
        val ru = readStrings(localeFile("values-ru"))
        val uk = readStrings(localeFile("values-uk"))

        val enScan = en.filterKeys(::isScanKey)
        val ruScan = ru.filterKeys(::isScanKey)
        val ukScan = uk.filterKeys(::isScanKey)

        assertFalse("no scan/chooser strings found in default locale", enScan.isEmpty())
        assertEquals("RU scan key set differs from EN", enScan.keys, ruScan.keys)
        assertEquals("UK scan key set differs from EN", enScan.keys, ukScan.keys)

        for ((locale, map) in listOf("EN" to en, "RU" to ru, "UK" to uk)) {
            for (key in enScan.keys) {
                assertTrue("$locale $key is blank", map.getValue(key).isNotBlank())
            }
        }

        for (key in referencedKeys()) {
            assertTrue("referenced $key is missing from default locale", en.containsKey(key))
        }
    }

    private fun isScanKey(key: String): Boolean =
        key.startsWith("hold_scan_") || key.startsWith("hold_chooser_")

    private fun localeFile(locale: String): File =
        moduleFile("src/main/res/$locale/hold_strings.xml")

    private fun referencedKeys(): Set<String> {
        val sources = listOf(
            "src/main/kotlin/dev/hryshyn/remanence/ui/scan/ScanScreen.kt",
            "src/main/kotlin/dev/hryshyn/remanence/ui/scan/AmbiguityChooserScreen.kt",
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
        val STRING_REFERENCE = Regex("""R\.string\.(hold_[a-z_]+)""")
    }
}
