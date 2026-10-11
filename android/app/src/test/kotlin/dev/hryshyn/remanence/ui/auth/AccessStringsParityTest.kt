package dev.hryshyn.remanence.ui.auth

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessStringsParityTest {
    @Test fun accessAndRecipientLabelsHaveEnglishRussianUkrainianParity() {
        for (name in listOf("access_design_strings.xml", "recipient_design_strings.xml", "capsule_export_strings.xml", "beta_strings.xml")) {
            val labels = listOf("values", "values-ru", "values-uk").map { locale ->
                val path = moduleFile("src/main/res/$locale/$name")
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path)
                    .getElementsByTagName("string")
                (0 until nodes.length).associate { index ->
                    val node = nodes.item(index) as org.w3c.dom.Element
                    node.getAttribute("name") to node.textContent
                }
            }
            assertTrue(labels.first().isNotEmpty())
            labels.forEach {
                assertEquals(labels.first().keys, it.keys)
                assertTrue(it.values.all(String::isNotBlank))
            }
        }
    }

    private fun moduleFile(relative: String): File {
        var directory: File? = File("").absoluteFile
        repeat(6) {
            directory?.let { base ->
                for (prefix in listOf("", "app/", "android/app/")) {
                    val candidate = File(base, prefix + relative)
                    if (candidate.exists()) return candidate
                }
            }
            directory = directory?.parentFile
        }
        error("Missing module file $relative")
    }
}
