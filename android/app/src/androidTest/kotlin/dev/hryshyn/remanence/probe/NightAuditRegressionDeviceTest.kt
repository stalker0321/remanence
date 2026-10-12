package dev.hryshyn.remanence.probe

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hryshyn.remanence.core.data.network.SafetyState
import dev.hryshyn.remanence.ui.capsule.CapsuleExportFiles
import dev.hryshyn.remanence.ui.hold.HoldTheme
import dev.hryshyn.remanence.ui.home.HomeScreen
import dev.hryshyn.remanence.ui.home.BackendHealthUiState
import dev.hryshyn.remanence.ui.home.AccountCapabilityState
import dev.hryshyn.remanence.ui.locale.AppLocale
import dev.hryshyn.remanence.ui.settings.BetaTermsGateWithActions
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled production UI fixtures, NOT actual provider or beta HTTP proof. */
@RunWith(AndroidJUnit4::class)
class NightAuditRegressionDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val evidence get() = File(context.getExternalFilesDir(null), "t179-fixture-evidence").apply { mkdirs() }

    @Test fun smallHomeWithLargeNativeTypeKeepsWholeLanguageOptionsReachable() {
        var selected = AppLocale.SYSTEM
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                HoldTheme { Box(Modifier.width(320.dp).height(640.dp)) {
                    HomeScreen(BackendHealthUiState.AVAILABLE, AccountCapabilityState.NotAuthenticated,
                        publicEntry = true, onLocaleSelected = { selected = it })
                } }
            }
        }
        rule.onNodeWithTag("language_option_ukrainian").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(AppLocale.UKRAINIAN, selected) }
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("Українська").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertEquals(1, layout.lineCount)
        assertFalse(layout.isLineEllipsized(0))
        assertTrue(layout.getLineRight(0) <= layout.size.width + 1)
        capture("home-small-large-type-language")
    }

    @Test fun legalLinkAfterFailedFetchPreservesRetryAndServerAuthority() {
        var calls = 0
        val retry = CompletableDeferred<SafetyState?>()
        rule.setContent { HoldTheme {
            BetaTermsGateWithActions("fixture-owner", { if (++calls == 1) null else retry.await() },
                { true }, { true }, {}) { Text("protected", Modifier.testTag("fixture_protected")) }
        } }
        rule.onNodeWithTag("beta_terms_retry").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("beta_terms_link").performScrollTo().performClick()
        rule.onNodeWithTag("beta_terms_retry").performScrollTo().assertIsEnabled()
        capture("legal-link-retains-retry")
        rule.onNodeWithTag("beta_terms_retry").performClick().assertIsNotEnabled()
        rule.runOnIdle { retry.complete(SafetyState("2026-10-11", false, emptyList())) }
        rule.onNodeWithTag("beta_terms_agreement").performScrollTo().performClick()
        rule.onNodeWithTag("beta_terms_continue").assertIsEnabled()
        rule.onNodeWithTag("fixture_protected").assertDoesNotExist()
        assertEquals(2, calls)
    }

    @Test fun explicitShareLeaseExpiresWithoutAnotherExportAndLogoutPurgesIt() {
        val fresh = CapsuleExportFiles.write(context, byteArrayOf(1))
        CapsuleExportFiles.sweep(context, fresh.lastModified() + CapsuleExportFiles.TTL_MILLIS - 1)
        assertTrue(fresh.exists())
        CapsuleExportFiles.sweep(context, fresh.lastModified() + CapsuleExportFiles.TTL_MILLIS)
        assertFalse(fresh.exists())
        val next = CapsuleExportFiles.write(context, byteArrayOf(2))
        CapsuleExportFiles.sweep(context, clearAll = true)
        assertFalse(next.exists())
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(evidence, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
