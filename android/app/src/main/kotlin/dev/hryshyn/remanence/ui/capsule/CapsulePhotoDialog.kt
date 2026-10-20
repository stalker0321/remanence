package dev.hryshyn.remanence.ui.capsule

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val closeFocus = remember { FocusRequester() }
    fun move(delta: Int) {
        val next = pager.currentPage + delta
        if (next in photoIds.indices) scope.launch { pager.scrollToPage(next) }
    }
    LaunchedEffect(pager.currentPage) {
        try { validate() } catch (_: Exception) { lost() }
    }
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.dp * .9f
    val maxHeight = configuration.screenHeightDp.dp * .7f
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        val inputMode = LocalInputModeManager.current.inputMode
        LaunchedEffect(inputMode) {
            // Material buttons accept keyboard focus only in keyboard mode.
            // Entering that mode is an event, not a delayed focus retry.
            closeFocus.requestFocus()
        }
        val previousLabel = stringResource(R.string.media_photo_previous)
        val nextLabel = stringResource(R.string.media_photo_next)
        val positionLabel = stringResource(R.string.media_photo_position, pager.currentPage + 1, photoIds.size)
        Column(Modifier.width(width).onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.DirectionLeft -> { move(-1); true }
                Key.DirectionRight -> { move(1); true }
                else -> false
            }
        }.testTag("capsule_photo_dialog"),
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
                IconButton(onClick = { move(-1) }, enabled = pager.currentPage > 0,
                    modifier = Modifier.testTag("capsule_photo_previous").semantics { contentDescription = previousLabel }) {
                    Text("←", Modifier.clearAndSetSemantics {}, color = HoldColors.Paper)
                }
                Text("${pager.currentPage + 1} / ${photoIds.size}", color = HoldColors.Paper,
                    modifier = Modifier.testTag("capsule_photo_page_count").semantics { contentDescription = positionLabel })
                IconButton(onClick = { move(1) }, enabled = pager.currentPage < photoIds.lastIndex,
                    modifier = Modifier.testTag("capsule_photo_next").semantics { contentDescription = nextLabel }) {
                    Text("→", Modifier.clearAndSetSemantics {}, color = HoldColors.Paper)
                }
                HoldTextButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus)
                    .focusProperties { canFocus = true }.testTag("capsule_photo_close")) {
                    Text(stringResource(R.string.hold_close), color = HoldColors.Paper)
                }
            }
        }
    }
}
