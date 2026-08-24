package app.postmark.memory

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Regression guard for security.md section 5: Auto Backup and data extraction
 * must stay disabled for every storage domain so wrapped identity keysets,
 * sealed tokens, staged/decrypted media, fingerprint keys, and ciphertext
 * files are never copied off the device.
 */
class ManifestBackupPolicyTest {

    @Test
    fun manifestDisablesBackupWithFailSafeExtractionRules() {
        val application = manifestApplicationElement()
        val androidNs = "http://schemas.android.com/apk/res/android"

        assertEquals("false", application.getAttributeNS(androidNs, "allowBackup"))

        val fullRulesName = resourceFileName(application, androidNs, "fullBackupContent")
        val extractionRulesName = resourceFileName(application, androidNs, "dataExtractionRules")
        val legacyRules = parse(resXml(fullRulesName))
        val extractionRules = parse(resXml(extractionRulesName))

        assertEveryDomainExcluded(
            requiredChildren(legacyRules, "full-backup-content", "exclude"),
            LEGACY_DOMAINS,
            "$fullRulesName/full-backup-content",
        )

        for (mode in listOf("cloud-backup", "device-transfer")) {
            assertEveryDomainExcluded(
                requiredChildren(extractionRules, mode, "exclude"),
                EXTRACTION_DOMAINS,
                "$extractionRulesName/$mode",
            )
        }
    }

    private fun manifestApplicationElement(): Element {
        val manifest = parse(File(moduleRoot(), "src/main/AndroidManifest.xml"))
        val applications = manifest.getElementsByTagName("application")
        assertEquals("exactly one <application> expected", 1, applications.length)
        return applications.item(0) as Element
    }

    private fun resourceFileName(application: Element, ns: String, attribute: String): String {
        val reference = application.getAttributeNS(ns, attribute)
        assertTrue("$attribute must reference an xml resource, was '$reference'", reference.startsWith("@xml/"))
        return reference.removePrefix("@xml/")
    }

    private fun resXml(name: String): File = File(moduleRoot(), "src/main/res/xml/$name.xml")

    private fun requiredChildren(document: org.w3c.dom.Document, tag: String, childTag: String): List<Element> {
        val sections = document.getElementsByTagName(tag)
        assertEquals("expected one <$tag>", 1, sections.length)
        val section = sections.item(0) as Element
        val excluded = mutableListOf<Element>()
        for (index in 0 until section.childNodes.length) {
            val node = section.childNodes.item(index)
            if (node is Element && node.tagName == childTag) {
                excluded.add(node)
            }
        }
        return excluded
    }

    private fun assertEveryDomainExcluded(
        excludes: List<Element>,
        domains: Set<String>,
        context: String,
    ) {
        val excludedDomains = excludes.mapNotNull { it.getAttribute("domain") }.toSet()
        val missing = domains - excludedDomains
        assertTrue("$context must exclude $missing (deny-by-default)", missing.isEmpty())
        for (exclude in excludes) {
            val domain = exclude.getAttribute("domain")
            assertNotNull("every exclude must name a domain", domain)
            assertEquals(
                "$context must exclude the whole '$domain' domain (no path carve-outs)",
                "",
                exclude.getAttribute("path"),
            )
        }
    }

    private fun moduleRoot(): File {
        var directory = File(System.getProperty("user.dir"))
        repeat(6) {
            if (File(directory, "src/main/AndroidManifest.xml").exists()) {
                return directory
            }
            directory = directory.parentFile ?: return@repeat
        }
        throw IllegalStateException("app module root with src/main/AndroidManifest.xml not found")
    }

    private fun parse(file: File): org.w3c.dom.Document {
        assertTrue("missing rules file ${file.path}", file.exists())
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return builder.newDocumentBuilder().parse(file)
    }

    private companion object {
        val LEGACY_DOMAINS =
            setOf("root", "file", "database", "sharedpref", "external")
        val EXTRACTION_DOMAINS =
            LEGACY_DOMAINS + setOf("device_root", "device_file", "device_database", "device_sharedpref")
    }
}
