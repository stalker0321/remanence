package dev.hryshyn.remanence.ui.locale

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Manifest contract for the observed cold-process first locale
 * switch. Actual device evidence (`wm_relaunch_resume_activity
 * [...MainActivity,2104]`) proves the first System→explicit change
 * carries mask 0x2104 = LOCALE | SCREEN_LAYOUT |
 * LAYOUT_DIRECTION; the manifest must handle all three, or the
 * Activity (and its window) is destroyed for a black gap. This is
 * manifest contract proof ONLY — real first cold-process replay
 * and rotation/native evidence are owned separately.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivityLocaleConfigContractTest {

    /** Observed first-switch relaunch mask, from SDK constants. */
    private val observedFirstSwitchMask =
        ActivityInfo.CONFIG_LOCALE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or
            ActivityInfo.CONFIG_LAYOUT_DIRECTION

    @Test
    @Config(sdk = [35])
    fun declaredMaskHandlesObservedFirstSwitchOn35() {
        assertDeclaredMaskContract()
    }

    @Test
    @Config(sdk = [28])
    fun declaredMaskHandlesObservedFirstSwitchOn28() {
        assertDeclaredMaskContract()
    }

    private fun assertDeclaredMaskContract() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        val declared = info.configChanges
        // The exact observed first-switch mask is fully handled.
        assertEquals(
            "MainActivity must handle LOCALE|SCREEN_LAYOUT|LAYOUT_DIRECTION, was 0x" +
                declared.toString(16),
            observedFirstSwitchMask,
            declared and observedFirstSwitchMask,
        )
        // Ordinary recreation triggers stay unhandled: no blanket.
        assertEquals(0, declared and ActivityInfo.CONFIG_ORIENTATION)
        assertEquals(0, declared and ActivityInfo.CONFIG_SCREEN_SIZE)
        assertEquals(
            "no config blanket beyond the locale triple",
            observedFirstSwitchMask,
            declared,
        )
    }
}
