package dev.hryshyn.remanence.ui.locale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldColors

/**
 * Discoverable in-app language switch (EN/RU/UK + System reset).
 *
 * Hold visual language: no new settings surface, no provider chrome — a flat
 * labeled group of text buttons in the ambient [HoldColors]/Hold typography
 * (inherited from [MaterialTheme], hence the DejaVu/Cyrillic Hold font rules
 * keep applying). Labels are self-names (English / Русский / Українська)
 * plus the localized System reset entry under a small localized caption.
 *
 * Accessibility: the options form one [selectableGroup]; each option is a
 * [selectable] radio ([Role.RadioButton] + selected state), so TalkBack
 * announces e.g. "Русский, selected, radio button, double-tap to select".
 * The flat text look and 48 dp target stay in the Hold visual language.
 *
 * Stateless: the host hoists [current] and persists via
 * [AppLocaleController.select]; this row only reports taps. It is mounted at
 * the bottom of Home (all auth states) and in the Auth footer, so it is
 * reachable before sign-in without disturbing auth/capsule state.
 */
@Composable
fun LanguageSwitchRow(
    current: AppLocale,
    onSelect: (AppLocale) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("language_switch_row"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.hold_language_label),
            style = MaterialTheme.typography.labelSmall,
            color = HoldColors.Muted,
            modifier = Modifier
                .testTag("language_group_label")
                .semantics { heading() },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .testTag("language_select_group"),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LanguageOption(
                locale = AppLocale.SYSTEM,
                label = stringResource(R.string.hold_language_system),
                selected = current == AppLocale.SYSTEM,
                onSelect = onSelect,
            )
            LanguageOption(
                locale = AppLocale.ENGLISH,
                label = stringResource(R.string.hold_language_english),
                selected = current == AppLocale.ENGLISH,
                onSelect = onSelect,
            )
            LanguageOption(
                locale = AppLocale.RUSSIAN,
                label = stringResource(R.string.hold_language_russian),
                selected = current == AppLocale.RUSSIAN,
                onSelect = onSelect,
            )
            LanguageOption(
                locale = AppLocale.UKRAINIAN,
                label = stringResource(R.string.hold_language_ukrainian),
                selected = current == AppLocale.UKRAINIAN,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun LanguageOption(
    locale: AppLocale,
    label: String,
    selected: Boolean,
    onSelect: (AppLocale) -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .testTag("language_option_${locale.name.lowercase()}")
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = selected,
                onClick = { onSelect(locale) },
                role = Role.RadioButton,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) HoldColors.Ink else HoldColors.Muted,
        )
    }
}
