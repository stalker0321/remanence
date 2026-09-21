package dev.hryshyn.remanence.ui.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Store contract for the in-app language switch against the single source of
 * truth (AppCompat storage).
 *
 * Runs below API 33 on purpose: there AppCompat keeps the requested locales
 * in a process static with no delegate or platform involvement (verified
 * against the 1.7.0 bytecode), so set/get round-trips deterministically
 * without framework doubles. The mapping, reset, and fallback decisions under
 * test are SDK-independent; the API 33+ platform path is Google-tested code
 * reached through the same [AppLocale]/list mapping.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class AppLocaleRepositoryTest {

    private lateinit var repository: AppLocaleRepository

    @Before
    fun resetStore() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        repository = AppLocaleRepository()
    }

    @Test
    fun defaultIsSystemWhenNothingPersisted() {
        assertEquals(AppLocale.SYSTEM, repository.load())
        assertEquals("", repository.currentTag())
    }

    @Test
    fun englishRussianUkrainianRoundTrip() {
        for (locale in listOf(AppLocale.ENGLISH, AppLocale.RUSSIAN, AppLocale.UKRAINIAN)) {
            repository.persist(locale)
            assertEquals(locale, repository.load())
            assertEquals(locale.languageTag, repository.currentTag())
        }
    }

    @Test
    fun systemResetClearsToBlankTag() {
        repository.persist(AppLocale.RUSSIAN)
        assertEquals(AppLocale.RUSSIAN, repository.load())
        repository.persist(AppLocale.SYSTEM)
        assertEquals(AppLocale.SYSTEM, repository.load())
        assertEquals("", repository.currentTag())
    }

    @Test
    fun russianToSystemResetClearsTag() {
        repository.persist(AppLocale.RUSSIAN)
        assertEquals("ru", repository.currentTag())
        repository.persist(AppLocale.SYSTEM)
        assertEquals(AppLocale.SYSTEM, repository.load())
        assertEquals("", repository.currentTag())
    }

    @Test
    fun ukrainianToSystemResetClearsTag() {
        repository.persist(AppLocale.UKRAINIAN)
        assertEquals("uk", repository.currentTag())
        repository.persist(AppLocale.SYSTEM)
        assertEquals(AppLocale.SYSTEM, repository.load())
        assertEquals("", repository.currentTag())
    }

    @Test
    fun choiceSurvivesRepositoryRecreation() {
        repository.persist(AppLocale.UKRAINIAN)
        assertEquals(AppLocale.UKRAINIAN, AppLocaleRepository().load())
    }

    @Test
    fun unknownStoredTagFallsBackToSystem() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr"))
        assertEquals(AppLocale.SYSTEM, repository.load())
    }
}
