package dev.hryshyn.remanence.ui.scan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.hryshyn.remanence.ui.motion.AstraEnterTransition
import dev.hryshyn.remanence.ui.motion.AstraMotionSpec

/**
 * Chooser arrival branch: the already-mapped minimal-hint rows rendered
 * through the routine entry transition (`design/motion` arrival beat).
 *
 * The rows mapping (trusted handle, bidi-isolated claim, year/place
 * labels) and both actions stay exactly as the production [ScanScreen]
 * branch defines them — this wrapper only owns the arrival. Row content
 * itself remains covered by `AmbiguityChooserScreenTest`; the tests here
 * pin that the branch arrives (FULL and reduced regimes) with rows and
 * actions intact.
 */
@Composable
internal fun ScanChooserBranch(
    rows: List<ChooserHintRow>,
    onSelected: (ChooserHintRow) -> Unit,
    onRecapture: () -> Unit,
    motion: AstraMotionSpec.Resolved,
    modifier: Modifier = Modifier,
) {
    AstraEnterTransition(motion = motion) {
        AmbiguityChooserScreen(
            rows = rows,
            onSelected = onSelected,
            onRecapture = onRecapture,
            modifier = modifier,
        )
    }
}
