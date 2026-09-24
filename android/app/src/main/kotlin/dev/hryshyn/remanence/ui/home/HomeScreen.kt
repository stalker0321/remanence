package dev.hryshyn.remanence.ui.home

import androidx.compose.ui.res.stringResource
import dev.hryshyn.remanence.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.hryshyn.remanence.ui.hold.HoldActionObject
import dev.hryshyn.remanence.ui.hold.HoldSpace
import dev.hryshyn.remanence.ui.locale.AppLocale
import dev.hryshyn.remanence.ui.locale.LanguageSwitchRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class BackendHealthUiState {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
}

/**
 * Combined account capability driving Home actions. Create/Scan stay disabled
 * unless the user is authenticated AND the local crypto identity is ready;
 * recovery-required accounts can browse nowhere and are told why.
 */
sealed interface AccountCapabilityState {
    data object NotAuthenticated : AccountCapabilityState

    /** Authenticated on the server, but private identity keys are absent locally. */
    data object RecoveryRequired : AccountCapabilityState

    data class CryptoReady(
        val userId: String,
        val handle: String,
    ) : AccountCapabilityState

    val actionsEnabled: Boolean
        get() = this is CryptoReady
}

/** Astra validation flex shares: .home-intent{flex:1.25 0 auto} + .make{flex:.83 0 auto}. */
private const val SCAN_FLEX = 1.25f
private const val CREATE_FLEX = 0.83f

@Composable
fun HomeScreen(
    state: BackendHealthUiState,
    accountCapability: AccountCapabilityState = AccountCapabilityState.NotAuthenticated,
    onCreate: () -> Unit = {},
    onScan: () -> Unit = {},
    publicEntry: Boolean = false,
    appLocale: AppLocale = AppLocale.SYSTEM,
    onLocaleSelected: (AppLocale) -> Unit = {},
    /**
     * Whether the recovery note shows. Defaults to the capability, but the
     * caller passes the union with the synchronous auth state so the note is
     * decided on the same frame the home surface appears — never popping in
     * a frame later and shifting the action cards.
     */
    showRecoveryNote: Boolean = accountCapability == AccountCapabilityState.RecoveryRequired,
) {
    val enabled = accountCapability.actionsEnabled ||
        (publicEntry && accountCapability == AccountCapabilityState.NotAuthenticated)
    val gutter = HoldSpace.pageGutter()
    val compactHome = HoldSpace.compactHomeDisplay()
    val narrowHome = LocalConfiguration.current.screenWidthDp < 330
    val scanFloor = if (narrowHome) 253.dp else 245.dp
    val createFloor = if (narrowHome) 237.dp else 222.dp
    val recoveryRequired = showRecoveryNote
    if (compactHome) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = gutter, vertical = HoldSpace.Gutter),
            verticalArrangement = Arrangement.spacedBy(HoldSpace.Group),
        ) {
            HomeBrand(recoveryRequired = recoveryRequired)
            HomeScanCard(
                enabled = enabled, onScan = onScan,
                minSurfaceHeight = scanFloor, compact = true, tag = "scan_action",
            )
            HomeCreateCard(
                enabled = enabled, onCreate = onCreate,
                minSurfaceHeight = createFloor, tag = "create_action",
            )
            LanguageSwitchRow(
                current = appLocale,
                onSelect = onLocaleSelected,
            )
        }
        return
    }
    // Non-compact: CSS flex-basis auto. Bases are max(content, floor) and
    // ONLY the surplus over bases splits SCAN_FLEX/CREATE_FLEX. Compose
    // weight() would split the TOTAL height and skew both the scan-create
    // diff and the extra-height ratio, so intrinsics are measured via
    // SubcomposeLayout and the surplus is distributed here.
    SubcomposeLayout(
        modifier = Modifier.fillMaxSize().padding(horizontal = gutter, vertical = HoldSpace.Gutter),
    ) { constraints ->
        val maxW = constraints.maxWidth
        val wrap = Constraints(maxWidth = maxW)
        val gapPx = HoldSpace.Group.toPx().toInt()
        val brandPlaceables = subcompose("homeBrand") { HomeBrand(recoveryRequired = recoveryRequired) }
            .map { it.measure(wrap) }
        val langPlaceables = subcompose("homeLang") {
            LanguageSwitchRow(current = appLocale, onSelect = onLocaleSelected)
        }.map { it.measure(wrap) }
        // Intrinsic pass: natural content heights; floors stay minima.
        // Measured copies carry no test tag and cleared semantics so the
        // placed copies stay the unique nodes tests and a11y observe.
        val scanNatural = subcompose("homeScanIntrinsic") {
            HomeScanCard(enabled = enabled, onScan = onScan, minSurfaceHeight = scanFloor, compact = false, tag = null)
        }.map { it.measure(Constraints(maxWidth = maxW, maxHeight = Constraints.Infinity)) }
            .sumOf { it.height }
        val createNatural = subcompose("homeCreateIntrinsic") {
            HomeCreateCard(enabled = enabled, onCreate = onCreate, minSurfaceHeight = createFloor, tag = null)
        }.map { it.measure(Constraints(maxWidth = maxW, maxHeight = Constraints.Infinity)) }
            .sumOf { it.height }
        val scanFloorPx = scanFloor.toPx().toInt()
        val createFloorPx = createFloor.toPx().toInt()
        val baseScan = maxOf(scanNatural, scanFloorPx)
        val baseCreate = maxOf(createNatural, createFloorPx)
        val leavesH = brandPlaceables.sumOf { it.height } + langPlaceables.sumOf { it.height }
        // Brand, [recovery], scan, create, language with one gap between each.
        val gapsH = gapPx * (brandPlaceables.size + langPlaceables.size + 1)
        val surplus = if (constraints.hasBoundedHeight) {
            maxOf(0, constraints.maxHeight - leavesH - gapsH - baseScan - baseCreate)
        } else {
            0
        }
        // Remainder goes to scan so the column exactly fills the viewport.
        val createExtra = (surplus * (CREATE_FLEX / (SCAN_FLEX + CREATE_FLEX))).toInt()
        val scanExtra = surplus - createExtra
        val scanH = baseScan + scanExtra
        val createH = baseCreate + createExtra
        val scanPlaceables = subcompose("homeScan") {
            HomeScanCard(enabled = enabled, onScan = onScan, minSurfaceHeight = scanFloor, compact = false, tag = "scan_action")
        }.map { it.measure(Constraints.fixed(maxW, scanH)) }
        val createPlaceables = subcompose("homeCreate") {
            HomeCreateCard(enabled = enabled, onCreate = onCreate, minSurfaceHeight = createFloor, tag = "create_action")
        }.map { it.measure(Constraints.fixed(maxW, createH)) }
        val totalH = if (constraints.hasBoundedHeight) constraints.maxHeight else {
            leavesH + gapsH + scanH + createH
        }
        layout(maxW, totalH) {
            var y = 0
            brandPlaceables.forEach { it.placeRelative(0, y); y += it.height }
            y += gapPx
            scanPlaceables.forEach { it.placeRelative(0, y); y += it.height }
            y += gapPx
            createPlaceables.forEach { it.placeRelative(0, y); y += it.height }
            y += gapPx
            langPlaceables.forEach { it.placeRelative(0, y); y += it.height }
        }
    }
}

@Composable
private fun HomeBrand(recoveryRequired: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(HoldSpace.Group)) {
        Text("remanence", style = MaterialTheme.typography.titleLarge)
        if (recoveryRequired) {
            Text(
                "Private keys for this account are not on this device; recovery required.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("home_recovery_note"),
            )
        }
    }
}

@Composable
private fun HomeScanCard(
    enabled: Boolean,
    onScan: () -> Unit,
    minSurfaceHeight: Dp,
    compact: Boolean,
    tag: String? = null,
) {
    HoldActionObject(
        title = stringResource(R.string.hold_home_open_title),
        detail = stringResource(R.string.hold_home_open_body),
        action = stringResource(R.string.hold_scan), onClick = onScan, enabled = enabled,
        compact = compact,
        minSurfaceHeight = minSurfaceHeight,
        modifier = Modifier.fillMaxWidth().then(
            tag?.let { Modifier.testTag(it) } ?: Modifier.clearAndSetSemantics {},
        ),
    )
}

@Composable
private fun HomeCreateCard(
    enabled: Boolean,
    onCreate: () -> Unit,
    minSurfaceHeight: Dp,
    tag: String? = null,
) {
    HoldActionObject(
        title = stringResource(R.string.hold_home_make_title),
        detail = stringResource(R.string.hold_home_make_body),
        action = stringResource(R.string.hold_make), onClick = onCreate, enabled = enabled,
        secondary = true, compact = true,
        minSurfaceHeight = minSurfaceHeight,
        modifier = Modifier.fillMaxWidth().then(
            tag?.let { Modifier.testTag(it) } ?: Modifier.clearAndSetSemantics {},
        ),
    )
}
