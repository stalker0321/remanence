package dev.hryshyn.remanence.ui.locale

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Bridge contract: [AppLocale] maps to the AppCompat locale list without
 * loss, SYSTEM maps to the empty list (follow device), and every supported
 * tag survives the platform handoff.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppLocaleControllerTest {

    @Test
    fun systemMapsToEmptyLocaleList() {
        assertTrue(AppLocaleController.toLocaleList(AppLocale.SYSTEM).isEmpty)
    }

    @Test
    fun englishRussianUkrainianMapToSingleLanguageTags() {
        assertEquals("en", AppLocaleController.toLocaleList(AppLocale.ENGLISH).toLanguageTags())
        assertEquals("ru", AppLocaleController.toLocaleList(AppLocale.RUSSIAN).toLanguageTags())
        assertEquals("uk", AppLocaleController.toLocaleList(AppLocale.UKRAINIAN).toLanguageTags())
    }
}
