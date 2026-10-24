package dev.hryshyn.remanence.ui.capsule

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class MediaStringsParityTest {
    @Test fun mediaAndReceiverCopyHaveMatchingKeysAndPlaceholdersInAllLocales() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.flatMap { base ->
            sequenceOf(File(base, "src/main/res"), File(base, "app/src/main/res"), File(base, "android/app/src/main/res"))
        }.first { File(it, "values/capsule_media_strings.xml").exists() }
        for (name in listOf("capsule_media_strings.xml", "receiver_scan_strings.xml")) {
            val catalogs = listOf("values", "values-ru", "values-uk").map { locale ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "$locale/$name"))
                    .getElementsByTagName("string")
                (0 until nodes.length).map { nodes.item(it) as Element }
                    .associate { it.getAttribute("name") to it.textContent }.also { assertEquals(nodes.length, it.size) }
            }
            catalogs.drop(1).forEach { translated ->
                assertEquals(catalogs[0].keys, translated.keys)
                catalogs[0].forEach { (key, value) ->
                    val text = translated.getValue(key)
                    assertTrue(text.isNotBlank()); assertNotEquals(value, text)
                    val placeholders = Regex("%[1-9][0-9]*\\$[ds]")
                    assertEquals(placeholders.findAll(value).map { it.value }.toList(), placeholders.findAll(text).map { it.value }.toList())
                }
            }
        }
        // Receiver gets a dedicated instruction; existing sender instruction stays correct.
        listOf("values", "values-ru", "values-uk").forEach { locale ->
            val sender = File(root, "$locale/create_flow.xml").readText()
            val expected = when (locale) { "values-ru" -> "прежде чем отправить"; "values-uk" -> "перш ніж надіслати"; else -> "before you post" }
            assertTrue(sender.contains(expected))
            assertFalse(File(root, "$locale/receiver_scan_strings.xml").readText().contains(expected))
        }
    }
}
