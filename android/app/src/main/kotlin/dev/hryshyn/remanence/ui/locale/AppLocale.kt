package dev.hryshyn.remanence.ui.locale

import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * In-app language choice for the discoverable Home/Auth language switch.
 *
 * SYSTEM (empty BCP-47 tag) follows the device locale and is the default and
 * the reset target. EN/RU/UK pin the app to that language regardless of the
 * system setting. Tags are the single source of truth shared by the
 * AppCompat/platform store ([AppLocaleRepository]) and [AppLocaleController].
 */
enum class AppLocale(val languageTag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    RUSSIAN("ru"),
    UKRAINIAN("uk"),
    ;

    /**
     * AppCompat/platform list form. SYSTEM maps to the empty list, which both
     * LocaleManager and AppCompatDelegate interpret as "reset to system".
     * Pure mapping (no statics, no Context): safe in plain unit tests.
     */
    fun toLocaleListCompat(): LocaleListCompat =
        if (this == SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.create(Locale.forLanguageTag(languageTag))
        }

    companion object {
        /** Ordered options as rendered by [LanguageSwitchRow]. */
        val options: List<AppLocale> = listOf(SYSTEM, ENGLISH, RUSSIAN, UKRAINIAN)

        /**
         * Resolves a persisted tag. Blank or unknown tags fall back to SYSTEM
         * so a corrupt or future value can never strand the UI in an
         * untranslated state.
         */
        fun fromLanguageTag(tag: String?): AppLocale {
            if (tag.isNullOrBlank()) return SYSTEM
            return values().firstOrNull { it.languageTag == tag.trim().lowercase() } ?: SYSTEM
        }
    }
}
