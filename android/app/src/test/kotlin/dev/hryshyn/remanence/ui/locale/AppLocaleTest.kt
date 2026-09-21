package dev.hryshyn.remanence.ui.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping contract for the in-app language switch: BCP-47 tags are the
 * single source of truth, SYSTEM is the blank-tag default/reset, and unknown
 * input can never strand the UI outside EN/RU/UK.
 */
class AppLocaleTest {

    @Test
    fun supportedLocalesMapToBcp47Tags() {
        assertEquals("", AppLocale.SYSTEM.languageTag)
        assertEquals("en", AppLocale.ENGLISH.languageTag)
        assertEquals("ru", AppLocale.RUSSIAN.languageTag)
        assertEquals("uk", AppLocale.UKRAINIAN.languageTag)
    }

    @Test
    fun optionsRenderSystemFirstThenEnglishRussianUkrainian() {
        assertEquals(
            listOf(AppLocale.SYSTEM, AppLocale.ENGLISH, AppLocale.RUSSIAN, AppLocale.UKRAINIAN),
            AppLocale.options,
        )
    }

    @Test
    fun fromTagRoundTripsEverySupportedTag() {
        for (locale in AppLocale.values()) {
            assertEquals(locale, AppLocale.fromLanguageTag(locale.languageTag))
        }
    }

    @Test
    fun blankTagMeansSystemDefault() {
        assertEquals(AppLocale.SYSTEM, AppLocale.fromLanguageTag(null))
        assertEquals(AppLocale.SYSTEM, AppLocale.fromLanguageTag(""))
        assertEquals(AppLocale.SYSTEM, AppLocale.fromLanguageTag("   "))
    }

    @Test
    fun unknownTagFallsBackToSystem() {
        assertEquals(AppLocale.SYSTEM, AppLocale.fromLanguageTag("fr"))
        assertEquals(AppLocale.SYSTEM, AppLocale.fromLanguageTag("de-AT"))
        assertEquals(AppLocale.SYSTEM, AppLocale.fromLanguageTag("xx"))
    }

    @Test
    fun tagMatchingIsCaseInsensitiveAndTrimmed() {
        assertEquals(AppLocale.RUSSIAN, AppLocale.fromLanguageTag(" RU "))
        assertEquals(AppLocale.UKRAINIAN, AppLocale.fromLanguageTag("UK"))
        assertEquals(AppLocale.ENGLISH, AppLocale.fromLanguageTag("En"))
    }

    @Test
    fun systemMapsToEmptyListForReset() {
        assertTrue(AppLocale.SYSTEM.toLocaleListCompat().isEmpty)
    }

    @Test
    fun englishRussianUkrainianMapToSingleBareTags() {
        assertEquals("en", AppLocale.ENGLISH.toLocaleListCompat().toLanguageTags())
        assertEquals("ru", AppLocale.RUSSIAN.toLocaleListCompat().toLanguageTags())
        assertEquals("uk", AppLocale.UKRAINIAN.toLocaleListCompat().toLanguageTags())
    }
}
