package dev.hryshyn.remanence.ui.capsule

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Controlled registry callbacks, NOT OS CreateDocument/rotation evidence. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class CapsuleExportCallbackTest {
    @get:Rule val rule = createComposeRule()
    private class Registry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        var code: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) { code = requestCode }
        fun reply(uri: Uri?) { dispatchResult(requireNotNull(code), uri) }
    }
    private val registry = Registry()
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun show(): StateRestorationTester {
        val restoration = StateRestorationTester(rule)
        val export = CapsuleImageExport(Modifier, { bytes }, {})
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                HoldTheme { CapsuleImageExportButton(export, true) }
            }
        }
        rule.onNodeWithTag("capsule_share").performClick()
        rule.onNodeWithTag("capsule_export_file").performClick()
        rule.waitUntil(5_000) { registry.code != null }
        return restoration
    }

    @Test fun lostPendingBytesWithReturnedDocumentAreExplicitlyInterrupted() {
        val restoration = show()
        restoration.emulateSavedInstanceStateRestore()
        assertTrue("dispose must wipe plaintext, not save it", bytes.all { it == 0.toByte() })
        rule.runOnIdle { registry.reply(Uri.parse("content://synthetic/not-written")) }
        rule.onNodeWithText(context.getString(R.string.media_export_interrupted)).assertIsDisplayed()
        rule.onNodeWithTag("capsule_share").assertIsEnabled()
    }

    @Test fun cancellationAfterRestoreIsNotFailure() {
        show().emulateSavedInstanceStateRestore()
        rule.runOnIdle { registry.reply(null) }
        rule.onNodeWithText(context.getString(R.string.media_export_interrupted)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.capsule_export_failed)).assertDoesNotExist()
        rule.onNodeWithTag("capsule_share").assertIsEnabled()
    }

    @Test fun ordinaryCancelWipesBytesAndAllowsAnotherExport() {
        show()
        rule.runOnIdle { registry.reply(null) }
        rule.waitUntil(5_000) { bytes.all { it == 0.toByte() } }
        rule.onNodeWithText(context.getString(R.string.capsule_export_failed)).assertDoesNotExist()
        rule.onNodeWithTag("capsule_share").assertIsEnabled()
    }

    @Test fun writeFailureShowsFailureAndReleasesBytes() {
        show()
        rule.runOnIdle { registry.reply(Uri.parse("content://synthetic/not-writable")) }
        rule.waitUntil(5_000) { bytes.all { it == 0.toByte() } }
        rule.onNodeWithText(context.getString(R.string.capsule_export_failed)).assertIsDisplayed()
    }

    @Test fun ordinaryCallbackWritesTheCapturedBytesThenWipesThem() {
        show()
        val file = File(context.cacheDir, "controlled-save.png")
        rule.runOnIdle { registry.reply(Uri.fromFile(file)) }
        rule.waitUntil(5_000) { bytes.all { it == 0.toByte() } }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
        rule.onNodeWithText(context.getString(R.string.capsule_export_failed)).assertDoesNotExist()
    }
}
