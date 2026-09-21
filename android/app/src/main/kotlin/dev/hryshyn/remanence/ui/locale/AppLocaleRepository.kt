package dev.hryshyn.remanence.ui.locale

import androidx.appcompat.app.AppCompatDelegate

/**
 * The single source of truth for the in-app language choice.
 *
 * Storage is AppCompat's own record (which forwards to the platform
 * LocaleManager on API 33+): there is no parallel SharedPreferences file, so
 * the in-app switch, the system Settings per-app language screen, and cold
 * start can never disagree. Reads come only from
 * [AppCompatDelegate.getApplicationLocales]; writes go only through
 * [persist]. An empty list means SYSTEM (follow device); blank or unknown
 * tags can never strand the UI because [AppLocale.fromLanguageTag] falls back
 * to SYSTEM.
 *
 * Lifecycle notes: on API 33+ the framework persists, applies, and recreates
 * for the new locales. Below 33 the attached AppCompat delegates (see
 * MainActivity) apply and restore the stored locales; the opt-in metadata in
 * the manifest enables disk persistence there. This class holds no Context
 * and mutates no process-global state.
 */
class AppLocaleRepository {

    fun load(): AppLocale {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return AppLocale.SYSTEM
        return AppLocale.fromLanguageTag(locales[0]?.language)
    }

    fun currentTag(): String = load().languageTag

    fun persist(locale: AppLocale) {
        AppCompatDelegate.setApplicationLocales(locale.toLocaleListCompat())
    }
}
