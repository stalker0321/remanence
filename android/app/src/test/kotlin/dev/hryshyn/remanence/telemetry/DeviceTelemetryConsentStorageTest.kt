package dev.hryshyn.remanence.telemetry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceTelemetryConsentStorageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun freshOffIndependentValuesSurviveNewStoreWithoutServerTermsReset() {
        val name = "telemetry-test-persistence"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        val store = DeviceTelemetryConsentStorage(context, name)
        assertEquals(TelemetryConsent(), store.load())
        assertTrue(store.save(TelemetryConsent(true, false)))
        assertEquals(TelemetryConsent(true, false), DeviceTelemetryConsentStorage(context, name).load())
        assertTrue(store.save(TelemetryConsent(false, true)))
        assertEquals(TelemetryConsent(false, true), DeviceTelemetryConsentStorage(context, name).load())
    }

    @Test fun unreadableTypedPreferenceMakesCoordinatorFailOff() {
        val name = "telemetry-test-corrupt"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().putString("design", "not-a-bool").putBoolean("crops", true).commit()
        val coordinator = TelemetryCoordinator(DeviceTelemetryConsentStorage(context, name), RecordingTelemetryTransport())
        assertEquals(TelemetryConsent(), coordinator.consent.value)
    }

    @Test fun allTelemetrySharedPreferencesAreExcludedFromLegacyCloudAndDeviceTransfer() {
        fun xml(name: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$name"))
        val legacy = xml("backup_rules.xml").getElementsByTagName("exclude")
        assertTrue((0 until legacy.length).any { legacy.item(it).attributes.getNamedItem("domain").nodeValue == "sharedpref" })
        val extraction = xml("data_extraction_rules.xml")
        for (parent in listOf("cloud-backup", "device-transfer")) {
            val excludes = (extraction.getElementsByTagName(parent).item(0) as org.w3c.dom.Element).getElementsByTagName("exclude")
            assertTrue(parent, (0 until excludes.length).any { excludes.item(it).attributes.getNamedItem("domain").nodeValue == "sharedpref" })
        }
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:allowBackup=\"false\""))
    }
}
