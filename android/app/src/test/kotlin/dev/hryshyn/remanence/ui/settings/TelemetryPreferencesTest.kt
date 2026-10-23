package dev.hryshyn.remanence.ui.settings

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.hryshyn.remanence.telemetry.*
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.locale.AppLocale
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
class TelemetryPreferencesTest {
    @get:Rule val rule = createComposeRule()
    @Test fun optionalChoicesAreIndependentAndRequiredTermsNeverDependOnThem() {
        var consent by mutableStateOf(TelemetryConsent())
        var agreed by mutableStateOf(false)
        var continued = 0
        rule.setContent { HoldTheme {
            BetaTermsPage(agreed, false, true, false, { agreed = it }, {}, {}, {}, {}, { continued++ },
                telemetryContent = { TelemetryPreferencesPanel(consent, { consent = consent.copy(design = it) }, { consent = consent.copy(crops = it) }) })
        } }
        rule.onNodeWithTag("telemetry_design").performScrollTo().assertIsOff().assertHeightIsAtLeast(56.dp).performClick().assertIsOn()
        rule.onNodeWithTag("telemetry_crops").performScrollTo().assertIsOff().performClick().assertIsOn()
        rule.onNodeWithTag("telemetry_design").performScrollTo().performClick().assertIsOff()
        assertEquals(TelemetryConsent(false, true), consent)
        rule.onNodeWithTag("beta_terms_continue").assertIsNotEnabled()
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().performClick()
        rule.runOnIdle { consent = TelemetryConsent() }
        rule.onNodeWithTag("beta_terms_continue").assertIsEnabled().performClick()
        assertEquals(1, continued)
    }

    @Test fun settingsRecreationUsesDeviceStoreAndDoesNotSwitchEitherChoiceAutomatically() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "telemetry-ui-isolated"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        val transport = RecordingTelemetryTransport()
        var coordinator by mutableStateOf(TelemetryCoordinator(DeviceTelemetryConsentStorage(context, name), transport))
        val restore = StateRestorationTester(rule)
        restore.setContent { HoldTheme {
            SettingsScreen("owner", AppLocale.SYSTEM, {}, true, {}, {}, telemetryContent = { TelemetryPreferences(coordinator) })
        } }
        rule.onNodeWithTag("telemetry_design").assertDoesNotExist()
        rule.onNodeWithTag("settings_feedback_row").performScrollTo().performClick()
        rule.onNodeWithTag("telemetry_design").performScrollTo().assertIsOff().performClick().assertIsOn()
        rule.onNodeWithTag("telemetry_crops").performScrollTo().assertIsOff()
        restore.emulateSavedInstanceStateRestore()
        rule.runOnIdle { coordinator = TelemetryCoordinator(DeviceTelemetryConsentStorage(context, name), transport) }
        rule.onNodeWithTag("telemetry_design").performScrollTo().assertIsOn()
        rule.onNodeWithTag("telemetry_crops").performScrollTo().assertIsOff()
        rule.onNodeWithTag("telemetry_design").performScrollTo().performClick().assertIsOff()
        assertTrue(transport.reports.isEmpty())
    }

    @Test fun disclosureAndControlsRemainReachableAtLargeType() {
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.8f)) { HoldTheme {
            SettingsScreen("owner", AppLocale.SYSTEM, {}, true, {}, {}, telemetryContent = { TelemetryPreferencesPanel(TelemetryConsent(), {}, {}) })
        } } }
        rule.onNodeWithTag("settings_feedback_row").performScrollTo().performClick()
        rule.onNodeWithTag("telemetry_design").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        rule.onNodeWithTag("telemetry_crops").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        rule.onNodeWithTag("telemetry_disclosure").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("settings_logout").performScrollTo().assertIsDisplayed()
    }

    @Test fun EnglishRussianUkrainianHaveSameCompleteNonBlankDisclosureKeys() {
        fun strings(folder: String): Map<String, String> {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$folder/telemetry_strings.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
        }
        val english = strings("values")
        assertEquals(6, english.size)
        for (folder in listOf("values", "values-ru", "values-uk")) {
            val values = strings(folder)
            assertEquals(english.keys, values.keys); assertTrue(values.values.all { it.isNotBlank() })
        }
        assertTrue(english["telemetry_disclosure"]!!.contains("approximate face boxes"))
        assertTrue(english["telemetry_disclosure"]!!.contains("cannot be withdrawn"))
    }

    @Test fun failedPreferenceSaveShowsWarningAndTurnsBothChoicesOffUntilSuccessfulSave() {
        val store = MemoryConsent(TelemetryConsent(true, true)).apply { canSave = false }
        val owner = TelemetryCoordinator(store, RecordingTelemetryTransport())
        rule.setContent { HoldTheme {
            SettingsScreen("owner", AppLocale.SYSTEM, {}, true, {}, {}, telemetryContent = { TelemetryPreferences(owner) })
        } }
        rule.onNodeWithTag("settings_feedback_row").performScrollTo().performClick()
        rule.onNodeWithTag("telemetry_design").performScrollTo().performClick().assertIsOff()
        rule.onNodeWithTag("telemetry_crops").performScrollTo().assertIsOff()
        rule.onNodeWithTag("telemetry_save_failed").performScrollTo().assertIsDisplayed()
        assertEquals(TelemetryConsent(), owner.consent.value)
        rule.runOnIdle { store.canSave = true; owner.setDesign(false) }
        rule.onNodeWithTag("telemetry_save_failed").assertDoesNotExist()
        assertEquals(TelemetryConsent(), store.value)
    }
}
