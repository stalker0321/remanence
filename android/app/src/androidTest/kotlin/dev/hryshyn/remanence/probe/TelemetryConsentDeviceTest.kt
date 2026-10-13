package dev.hryshyn.remanence.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.telemetry.*
import dev.hryshyn.remanence.ui.collage.CollageCropSource
import dev.hryshyn.remanence.ui.create.*
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.locale.AppLocale
import dev.hryshyn.remanence.ui.settings.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated fake transport/store ONLY. Never reads or changes real app telemetry consent. */
@RunWith(AndroidJUnit4::class)
class TelemetryConsentDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidence get() = File(context.getExternalFilesDir(null), "t182-fixture-evidence").apply { mkdirs() }
    private class Store(var value: TelemetryConsent = TelemetryConsent(true, false)) : TelemetryConsentStorage {
        override fun load() = value
        override fun save(value: TelemetryConsent): Boolean { this.value = value; return true }
    }
    private class Transport : TelemetryTransport {
        val reports = mutableListOf<JsonObject>()
        override fun send(bytes: ByteArray): TelemetryPendingSend {
            reports += Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            bytes.fill(0)
            return TelemetryPendingSend {}
        }
    }
    private fun sources(): List<GeneratorPreviewSource> = (0..2).map { index ->
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff778899.toInt() + index * 0x00110000)
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output); output.toByteArray() }
        bitmap.recycle(); GeneratorPreviewSource("fixture-photo-$index", bytes, 1000, 1000)
    }
    private fun mountChooser(browse: Boolean) {
        val sources = sources()
        val input = GeneratorExpression.GeneratorInput("fixture-owner", 182L,
            sources.mapIndexed { i, source -> GeneratorExpression.PhotoRef(source.contentId, i, 1000, 1000,
                MessageDigest.getInstance("SHA-256").digest(source.jpegBytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }) }, null, null)
        // Distinct originals are a production input invariant. Catch an
        // ineligible fixture before it turns into an opaque chooser timeout.
        assertEquals(GeneratorExpression.InputValidation.Valid, GeneratorExpression.validate(input))
        val chooser = CollageChoiceSession()
        val transport = Transport()
        val owner = TelemetryCoordinator(Store(), transport)
        val collection = owner.newSession()
        val cropSource = CollageCropSource { requests -> requests.associate { it.contentId to GeneratorExpression.CropWindow(0, 0, 10000, 10000) } }
        var visible by mutableStateOf(false)
        fun shown(index: Int) {
            val choices = chooser.candidates.map { TelemetryChoice.normalized(it.expression.grammarId, it.styleId)!! }
            collection.shown(owner.admission(), chooser.buildGeneration, index, choices, TelemetryConfig.from(input), emptyList())
        }
        rule.setContent { HoldTheme {
            CollageChoiceHost(chooser, input, sources, null, null, cropSource,
                modifier = if (visible) Modifier.fillMaxSize().padding(16.dp) else Modifier.size(0.dp),
                onShown = ::shown,
                onSelect = { index -> assertTrue(chooser.select(index)); collection.selected(owner.admission(), chooser.buildGeneration, index, emptyList()) },
                contentWrapper = { canvas, nav -> if (visible) Column(Modifier.fillMaxSize()) { nav(); Box(Modifier.weight(1f)) { canvas() } } })
        } }
        try {
            rule.waitUntil(20_000) { !chooser.isPreparing && chooser.candidates.size > 1 }
            rule.waitForIdle()
            rule.runOnIdle { collection.selected(owner.admission(), chooser.buildGeneration, 0, emptyList()); assertTrue(transport.reports.isEmpty()); visible = true }
            rule.onNodeWithTag("collage_canvas").assertIsDisplayed()
            capture(if (browse) "browse-first" else "immediate-first")
            if (browse) {
                rule.onNodeWithTag("collage_next").performClick(); rule.waitForIdle(); capture("browse-second")
                rule.onNodeWithTag("collage_previous").performClick(); rule.waitForIdle(); capture("browse-return")
            }
            rule.onNodeWithTag("collage_select").assertIsEnabled().performClick()
            rule.runOnIdle {
                assertEquals(1, transport.reports.size)
                val sequence = transport.reports.single()["design"]!!.jsonObject["shown"]!!.jsonArray.map { it.jsonPrimitive.int }
                assertEquals(if (browse) listOf(0, 1, 0) else listOf(0), sequence)
                assertEquals(0, chooser.selectedIndex)
                // Explicit repeat without a browse/change is not another observation or report.
                collection.selected(owner.admission(), chooser.buildGeneration, 0, emptyList()); assertEquals(1, transport.reports.size)
            }
            File(evidence, if (browse) "browse.json" else "immediate.json").writeText(transport.reports.single().toString())
        } finally { owner.clearSessionData(); collection.close(); sources.forEach { it.jpegBytes.fill(0) } }
    }
    @Test fun hiddenPrepareThenRealABAExposesOnlyDrawnHistory() = mountChooser(true)
    @Test fun immediateAHasNoSyntheticBrowseHistory() = mountChooser(false)

    @Test fun optionalChoicesAndDisclosuresFitNarrowLargeTypeAllThreeLocales() {
        val store = Store(TelemetryConsent()); val transport = Transport(); val owner = TelemetryCoordinator(store, transport)
        var language by mutableStateOf("en")
        var agreed by mutableStateOf(false)
        var settings by mutableStateOf(false)
        rule.setContent {
            val localized = remember(language) { context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale(language)) }) }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                HoldTheme { Box(Modifier.width(320.dp).fillMaxHeight()) {
                    if (settings) SettingsScreen("fixture-owner", AppLocale.SYSTEM, {}, true, {}, {}, telemetryContent = { TelemetryPreferences(owner) })
                    else BetaTermsPage(agreed, false, true, false, { agreed = it }, {}, {}, {}, {}, {}, telemetryContent = { TelemetryPreferences(owner) })
                } }
            }
        }
        try {
            for (locale in listOf("en", "ru", "uk")) {
                rule.runOnIdle { language = locale; settings = false; agreed = false }
                for (tag in listOf("telemetry_design", "telemetry_crops")) {
                    rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsOff().assertHeightIsAtLeast(56.dp)
                    val layouts = mutableListOf<TextLayoutResult>()
                    rule.onNodeWithTag(tag + "_label", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertFalse("$locale $tag clipped", layouts.single().hasVisualOverflow)
                }
                capture("terms-$locale-large")
                rule.onNodeWithTag("beta_terms_continue").assertIsNotEnabled()
                rule.onNodeWithTag("beta_terms_agreement").performScrollTo().performClick()
                rule.onNodeWithTag("beta_terms_continue").assertIsEnabled()
                rule.runOnIdle { settings = true }
                rule.onNodeWithTag("telemetry_design").performScrollTo().performClick().assertIsOn()
                rule.onNodeWithTag("telemetry_crops").performScrollTo().assertIsOff()
                rule.onNodeWithTag("telemetry_disclosure").performScrollTo().assertIsDisplayed(); capture("settings-$locale-large")
                rule.onNodeWithTag("telemetry_design").performScrollTo().performClick().assertIsOff()
            }
            assertTrue(transport.reports.isEmpty())
            exportInstalledIcon()
        } finally { owner.clearSessionData() }
    }
    private fun capture(name: String) {
        rule.waitForIdle(); val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun exportInstalledIcon() {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xfff4f2ec.toInt())
        val icon = requireNotNull(context.getDrawable(R.drawable.remainder_foreground))
        icon.setBounds(0, 0, 512, 512); icon.draw(Canvas(bitmap))
        val target = File(evidence, "Remanence-play-icon-512.png")
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(512, bitmap.width); assertEquals(512, bitmap.height); assertTrue(target.length() > 0); bitmap.recycle()
    }
}
