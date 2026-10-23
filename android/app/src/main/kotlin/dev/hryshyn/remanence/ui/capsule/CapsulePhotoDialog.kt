package dev.hryshyn.remanence.ui.capsule

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import dev.hryshyn.remanence.R
import dev.hryshyn.remanence.ui.hold.HoldColors
import dev.hryshyn.remanence.ui.hold.HoldTextButton
import dev.hryshyn.remanence.ui.motion.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Full-window originals over the still-mounted capsule. These images belong to
 * its live presentation: no decode, pixel copy or saved-state cache. Revocation
 * removes this immediately, including paging. Dismissal never extends authority
 * with an exit animation; the undisturbed capsule is immediately underneath.
 */
@Composable
internal fun CapsulePhotoDialog(
    photoIds: List<String>, images: Map<String, ImageBitmap>, initialPhotoId: String,
    validateAccess: () -> Unit, onAccessLost: () -> Unit, onDismiss: () -> Unit,
    motion: AstraMotionSpec.Resolved = rememberAstraMotion(),
) {
    if (photoIds.isEmpty() || photoIds.any { images[it] == null }) return
    key(initialPhotoId, photoIds) {
        OriginalPhotoWindow(photoIds, images, initialPhotoId, validateAccess, onAccessLost, onDismiss, motion)
    }
}

@Composable
private fun OriginalPhotoWindow(
    photoIds: List<String>, images: Map<String, ImageBitmap>, initialPhotoId: String,
    validateAccess: () -> Unit, onAccessLost: () -> Unit, onDismiss: () -> Unit,
    motion: AstraMotionSpec.Resolved,
) {
    val pager = rememberPagerState(initialPage = photoIds.indexOf(initialPhotoId).coerceAtLeast(0)) { photoIds.size }
    val validate by rememberUpdatedState(validateAccess)
    val lost by rememberUpdatedState(onAccessLost)
    val scope = rememberCoroutineScope()
    val closeFocus = remember { FocusRequester() }
    var pagingJob by remember { mutableStateOf<Job?>(null) }
    var requestedPage by remember { mutableStateOf<Int?>(null) }
    var requestSerial by remember { mutableIntStateOf(0) }
    var dismissed by remember { mutableStateOf(false) }
    fun dismiss() { if (!dismissed) { dismissed = true; onDismiss() } }
    fun move(delta: Int) {
        val next = ((requestedPage ?: pager.targetPage) + delta).coerceIn(photoIds.indices)
        try { validate() } catch (_: Exception) { lost(); return }
        val serial = ++requestSerial
        requestedPage = next
        pagingJob?.cancel()
        pagingJob = scope.launch {
            try {
                if (motion == AstraMotionSpec.Resolved.FULL) pager.animateScrollToPage(next,
                    animationSpec = astraTween(AstraMotionSpec.PHOTO_PAGE_MS, AstraGentleEasing))
                else pager.scrollToPage(next)
            } finally {
                // A cancelled older request must not erase the latest intent.
                if (requestSerial == serial) requestedPage = null
            }
        }
    }
    LaunchedEffect(pager.currentPage) {
        try { validate() } catch (_: Exception) { lost() }
    }
    LaunchedEffect(motion) {
        if (motion != AstraMotionSpec.Resolved.FULL) {
            val target = requestedPage ?: pager.targetPage
            pagingJob?.cancel()
            pager.scrollToPage(target)
            requestedPage = null
        }
    }
    val arrival = remember { Animatable(if (motion == AstraMotionSpec.Resolved.FULL) 0f else 1f) }
    LaunchedEffect(motion) {
        if (motion == AstraMotionSpec.Resolved.FULL) arrival.animateTo(1f,
            astraTween(AstraMotionSpec.PHOTO_ENTER_MS, AstraGentleEasing))
        else arrival.snapTo(1f)
    }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        securePolicy = SecureFlagPolicy.SecureOn,
    )) {
        // Compose owns the sole entrance; no stacked platform window fade.
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val previous = window?.attributes?.windowAnimations
            window?.setWindowAnimations(0)
            onDispose { if (previous != null) window?.setWindowAnimations(previous) }
        }
        val inputMode = LocalInputModeManager.current.inputMode
        LaunchedEffect(inputMode) { closeFocus.requestFocus() }
        val previousLabel = stringResource(R.string.media_photo_previous)
        val nextLabel = stringResource(R.string.media_photo_next)
        val positionLabel = stringResource(R.string.media_photo_position, pager.settledPage + 1, photoIds.size)
        val destination = requestedPage ?: pager.targetPage
        Column(Modifier.fillMaxSize().background(HoldColors.Ink).safeDrawingPadding()
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                    Key.DirectionLeft -> { move(-1); true }
                    Key.DirectionRight -> { move(1); true }
                    Key.Escape -> { dismiss(); true }
                    else -> false
                }
            }.testTag("capsule_photo_dialog"), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                HoldTextButton(onClick = ::dismiss, modifier = Modifier.focusRequester(closeFocus)
                    .focusProperties { canFocus = true }.testTag("capsule_photo_close")) {
                    Text(stringResource(R.string.hold_close), color = HoldColors.Paper)
                }
            }
            // Whole available stage, no percentage constraint. Chrome never
            // covers an edge of the original, including tall images/landscape.
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f)
                .testTag("capsule_photo_pager").graphicsLayer {
                    val progress = if (motion == AstraMotionSpec.Resolved.FULL) arrival.value else 1f
                    alpha = .9f + .1f * progress
                    scaleX = .987f + .013f * progress
                    scaleY = scaleX
                }, key = { photoIds[it] }) { index ->
                Image(images.getValue(photoIds[index]),
                    contentDescription = stringResource(R.string.create_photo_selection_label, index + 1, photoIds.size),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().testTag("capsule_original_photo_$index"))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { move(-1) }, enabled = destination > 0,
                    modifier = Modifier.testTag("capsule_photo_previous").semantics { contentDescription = previousLabel }) {
                    Text("←", Modifier.clearAndSetSemantics {}, color = HoldColors.Paper.copy(alpha = if (destination > 0) 1f else .35f))
                }
                Text("${pager.settledPage + 1} / ${photoIds.size}", color = HoldColors.Paper,
                    modifier = Modifier.testTag("capsule_photo_page_count").semantics { contentDescription = positionLabel })
                IconButton(onClick = { move(1) }, enabled = destination < photoIds.lastIndex,
                    modifier = Modifier.testTag("capsule_photo_next").semantics { contentDescription = nextLabel }) {
                    Text("→", Modifier.clearAndSetSemantics {}, color = HoldColors.Paper.copy(alpha = if (destination < photoIds.lastIndex) 1f else .35f))
                }
            }
        }
    }
}
