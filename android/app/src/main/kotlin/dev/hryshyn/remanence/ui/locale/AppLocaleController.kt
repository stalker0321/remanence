package dev.hryshyn.remanence.ui.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The single write path for the in-app language switch.
 *
 * [select] pushes the choice into AppCompat storage (the single source of
 * truth; the framework mirror on API 33+) exactly once and performs NO manual
 * restart. The restart is owned elsewhere exactly once per tier: below 33
 * AppCompat itself recreates the host (`ActivityCompat.recreate` on a real
 * locale config change); on 33+ the framework recreates for the new app
 * locales. A manual `recreate()` here would fire a second full
 * destroy/create lifecycle — double camera/session disposal — which is why it
 * was removed (blocker 1). Either way ViewModels, auth state, and capsule
 * grants survive because only the Activity tree restarts.
 *
 * Selecting the already-active locale is a no-op (AppCompat short-circuits
 * equal lists, so no restart is requested at all).
 *
 * Must be called on the main thread.
 */
object AppLocaleController {

    fun toLocaleList(locale: AppLocale): LocaleListCompat = locale.toLocaleListCompat()

    fun select(locale: AppLocale): AppLocale {
        AppCompatDelegate.setApplicationLocales(toLocaleList(locale))
        return locale
    }
}
