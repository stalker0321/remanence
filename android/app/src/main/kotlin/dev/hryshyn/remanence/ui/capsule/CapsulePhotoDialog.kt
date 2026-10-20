package dev.hryshyn.remanence.ui.capsule

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldTextButton

/**
 * Views existing, grant-owned decoded originals, never another decrypt/cache.
 * The host composes this only while its presentation is Ready; revocation
 * removes the dialog immediately. No saved state or pixel copies survive it.
 */
@Composable
internal fun CapsulePhotoDialog(
    photoIds: List<String>,
    images: Map<String, ImageBitmap>,
    initialPhotoId: String,
    validateAccess: () -> Unit,
    onAccessLost: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (photoIds.isEmpty() || photoIds.any { images[it] == null }) return
    val pager = rememberPagerState(initialPage = photoIds.indexOf(initialPhotoId).coerceAtLeast(0)) { photoIds.size }
    val validate by rememberUpdatedState(validateAccess)
    val lost by rememberUpdatedState(onAccessLost)
    LaunchedEffect(pager.currentPage) {
        try { validate() } catch (_: Exception) { lost() }
    }
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.dp * .9f
    val maxHeight = configuration.screenHeightDp.dp * .7f
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Column(Modifier.width(width).testTag("capsule_photo_dialog"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            // One stable viewing stage: Fit contains each original while the
            // page counter and close target stay still across aspect ratios.
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(maxHeight)
                .testTag("capsule_photo_pager"), key = { photoIds[it] }) { index ->
                Image(images.getValue(photoIds[index]),
                    contentDescription = stringResource(R.string.create_photo_selection_label, index + 1, photoIds.size),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().testTag("capsule_original_photo_$index"))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${pager.currentPage + 1} / ${photoIds.size}", color = HoldColors.Paper,
                    modifier = Modifier.testTag("capsule_photo_page_count"))
                HoldTextButton(onClick = onDismiss, modifier = Modifier.testTag("capsule_photo_close")) {
                    Text(stringResource(R.string.hold_close), color = HoldColors.Paper)
                }
            }
        }
    }
}
