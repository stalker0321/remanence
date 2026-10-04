package dev.hryshyn.remanence.session

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Flow controls use actual, translated resource keys in every supported
 * locale. Create owns its arrow/close bar; Scan still uses the root bar.
 */
class HoldFlowHeaderStringsParityTest {

    @Test
    fun flowHeaderLabelKeysExistInEveryLocaleAndAreUsedWithoutEnglishLiterals() {
        val en = readStrings(localeFile("values"))
        val ru = readStrings(localeFile("values-ru"))
        val uk = readStrings(localeFile("values-uk"))

        for (key in FLOW_HEADER_LABEL_KEYS) {
            for ((locale, map) in listOf("EN" to en, "RU" to ru, "UK" to uk)) {
                assertTrue("$locale is missing $key", map.containsKey(key))
                assertTrue("$locale $key is blank", map.getValue(key).isNotBlank())
            }
            assertNotEquals("RU $key still carries the English value", en.getValue(key), ru.getValue(key))
            assertNotEquals("UK $key still carries the English value", en.getValue(key), uk.getValue(key))
        }

        val source = moduleFile("src/main/kotlin/dev/hryshyn/remanence/session/RootScreen.kt").readText() +
            moduleFile("src/main/kotlin/dev/hryshyn/remanence/ui/hold/HoldComponents.kt").readText()
        for (literal in HARDCODED_FLOW_HEADER_LABELS) {
            assertFalse("RootScreen.kt hardcodes \"$literal\"", source.contains("\"$literal\""))
        }
        for (key in FLOW_HEADER_LABEL_KEYS) {
            assertTrue("$key is not referenced by flow chrome", source.contains("R.string.$key"))
        }
    }

    private fun localeFile(locale: String): File =
        moduleFile("src/main/res/$locale/hold_strings.xml")

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
        val FLOW_HEADER_LABEL_KEYS = listOf("hold_back", "hold_close", "hold_scan", "hold_back_to_home")
        val HARDCODED_FLOW_HEADER_LABELS = listOf("Create", "Scan", "Back to Home")
    }
}
