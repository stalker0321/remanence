package dev.hryshyn.remanence.ui.hold

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R

enum class ProductSection { HOME, SENT, SETTINGS }

/** A shared, quiet destination row. Creation and scanning retain their focused flow headers. */
@Composable
fun ProductFrame(section: ProductSection, onHome: () -> Unit, onSent: () -> Unit,
                 onSettings: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(HoldColors.Paper)) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = HoldSpace.pageGutter(), vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(
                Triple(ProductSection.HOME, R.string.rework_nav_home, onHome),
                Triple(ProductSection.SENT, R.string.rework_nav_sent, onSent),
                Triple(ProductSection.SETTINGS, R.string.settings_design_title, onSettings),
            ).forEach { (item, label, action) ->
                val selected = item == section
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (selected) HoldColors.Field else HoldColors.Paper)
                    .selectable(selected, role = Role.Tab, onClick = action)
                    .testTag(when (item) {
                        ProductSection.HOME -> "root_home_button"
                        ProductSection.SENT -> "root_history_button"
                        ProductSection.SETTINGS -> "root_settings_button"
                    }).padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge,
                        color = if (selected) HoldColors.Ink else HoldColors.Muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}
