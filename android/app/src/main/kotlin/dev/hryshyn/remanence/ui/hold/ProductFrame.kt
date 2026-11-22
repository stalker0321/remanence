package dev.hryshyn.remanence.ui.hold

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R

enum class ProductSection { HOME, SENT, SETTINGS }

private class Destination(val section: ProductSection, val label: String, val icon: Int, val onClick: () -> Unit)

/**
 * A shared, quiet destination row: each section is an icon on a pill that
 * marks the current one, its word under it, as on the site's phone tab bar.
 * Creation and scanning retain their focused flow headers.
 */
@Composable
fun ProductFrame(section: ProductSection, onHome: () -> Unit, onSent: () -> Unit,
                 onSettings: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(HoldColors.Paper)) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        val destinations = listOf(
            Destination(ProductSection.HOME, stringResource(R.string.rework_nav_home), R.drawable.ic_tab_home, onHome),
            Destination(ProductSection.SENT, stringResource(R.string.rework_nav_sent), R.drawable.ic_tab_history, onSent),
            Destination(ProductSection.SETTINGS, stringResource(R.string.settings_design_title), R.drawable.ic_tab_settings, onSettings),
        )
        BoxWithConstraints(Modifier.fillMaxWidth().testTag("product_navigation").padding(horizontal = 8.dp, vertical = 6.dp)) {
            val measurer = rememberTextMeasurer()
            val style = MaterialTheme.typography.labelMedium
            val density = LocalDensity.current
            val labelWidth = destinations.maxOf { measurer.measure(AnnotatedString(it.label), style, maxLines = 1).size.width }
            // When the longest word no longer fits its third of the row (large
            // text, a narrow phone), every word goes at once and the icons stay;
            // the words still name the tabs to TalkBack.
            val labels = with(density) { labelWidth + 4.dp.toPx() <= maxWidth.toPx() / 3 }
            Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                destinations.forEach { destination ->
                    val selected = destination.section == section
                    val tint = if (selected) HoldColors.Ink else HoldColors.Muted
                    Column(
                        Modifier.weight(1f).heightIn(min = 56.dp)
                            .selectable(selected, role = Role.Tab, onClick = destination.onClick)
                            .then(if (labels) Modifier else Modifier.semantics { contentDescription = destination.label })
                            .testTag(destination.section.tag()).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Box(
                            Modifier.size(width = 60.dp, height = 32.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (selected) HoldColors.Lilac else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(destination.icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                        }
                        if (labels) Text(destination.label, style = style, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
