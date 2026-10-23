package dev.hryshyn.remanence.ui.hold

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R

enum class ProductSection { HOME, SENT, SETTINGS }

/** A shared, quiet destination row. Creation and scanning retain their focused flow headers. */
@Composable
fun ProductFrame(section: ProductSection, onHome: () -> Unit, onSent: () -> Unit,
                 onSettings: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(HoldColors.Paper)) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        val destinations = listOf(
            Triple(ProductSection.HOME, stringResource(R.string.rework_nav_home), onHome),
            Triple(ProductSection.SENT, stringResource(R.string.rework_nav_sent), onSent),
            Triple(ProductSection.SETTINGS, stringResource(R.string.settings_design_title), onSettings),
        )
        BoxWithConstraints(Modifier.fillMaxWidth().testTag("product_navigation")
            .padding(horizontal = HoldSpace.pageGutter(), vertical = 8.dp)) {
            val measurer = rememberTextMeasurer()
            val style = MaterialTheme.typography.labelLarge
            val density = LocalDensity.current
            val labelWidth = destinations.maxOf { measurer.measure(AnnotatedString(it.second), style, maxLines = 1).size.width }
            val needsMenu = with(density) { labelWidth + 16.dp.toPx() > (maxWidth.toPx() - 8.dp.toPx()) / 3 }
            if (needsMenu) {
                var menu by remember { mutableStateOf(false) }
                val current = destinations.single { it.first == section }
                val navigationLabel = stringResource(R.string.rework_navigation)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                    .background(HoldColors.Field).clickable(role = Role.Button, onClick = { menu = true })
                    .testTag("product_navigation_menu").padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(current.second, Modifier.weight(1f).testTag(section.tag()).semantics { selected = true },
                        style = style, color = HoldColors.Ink)
                    Text("≡", Modifier.padding(start = 16.dp).semantics { contentDescription = navigationLabel },
                        style = MaterialTheme.typography.titleLarge, color = HoldColors.Ink)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    destinations.forEach { (item, label, action) ->
                        DropdownMenuItem(text = { Text(label, style = style) }, onClick = { menu = false; action() },
                            modifier = Modifier.heightIn(min = 56.dp).testTag("navigation_destination_" + item.name)
                                .semantics { selected = item == section; role = Role.Tab })
                    }
                }
            } else Row(Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            destinations.forEach { (item, label, action) ->
                val selected = item == section
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (selected) HoldColors.Field else HoldColors.Paper)
                    .selectable(selected, role = Role.Tab, onClick = action)
                    .testTag(item.tag()).padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = style,
                        color = if (selected) HoldColors.Ink else HoldColors.Muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        }
    }
}

private fun ProductSection.tag() = when (this) {
    ProductSection.HOME -> "root_home_button"
    ProductSection.SENT -> "root_history_button"
    ProductSection.SETTINGS -> "root_settings_button"
}
