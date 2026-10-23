package dev.hryshyn.remanence.ui.capsule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldTextButton

/** Fit the complete authored sheet, not the surrounding phone viewport. */
internal fun capsuleSheetSize(width: Dp, height: Dp, ratio: Float): Pair<Dp, Dp> {
    require(width > 0.dp && height > 0.dp && ratio > 0f && ratio.isFinite())
    val sheetWidth = minOf(width, height * ratio)
    return sheetWidth to sheetWidth / ratio
}

/** Viewer furniture is deliberately outside the exported content layer. */
@Composable
internal fun CapsuleSheetFrame(export: CapsuleImageExport, enabled: Boolean, ratio: Float,
    onClose: () -> Unit, modifier: Modifier = Modifier, closeTag: String,
    actions: @Composable () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    sheet: @Composable (Modifier) -> Unit) {
    Column(modifier.fillMaxSize().background(HoldColors.Field)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CapsuleImageExportButton(export, enabled)
            HoldTextButton(onClick = onClose, modifier = Modifier.testTag(closeTag)) {
                Text(stringResource(R.string.hold_close))
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center) {
            if (maxWidth > 0.dp && maxHeight > 0.dp) {
                val (width, height) = capsuleSheetSize(maxWidth, maxHeight, ratio)
                Box(Modifier.size(width, height).shadow(8.dp, RoundedCornerShape(2.dp), clip = false)
                    .testTag("capsule_sheet")) {
                    sheet(Modifier.fillMaxSize().then(export.contentModifier))
                    overlay()
                }
            }
        }
        actions()
    }
}
