package dev.hryshyn.remanence.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import dev.hryshyn.remanence.ui.hold.HoldButton as Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import dev.hryshyn.remanence.ui.hold.HoldSecondaryButton as OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hryshyn.remanence.R

/**
 * One chooser hint row (docs/recognition.md section 9): only the locally
 * decrypted sender handle snapshot, year/date, and optional place label.
 * No thumbnails, notes, photo counts, or any gallery affordance exists here.
 */
data class ChooserHintRow(
    val candidateId: String,
    /**
     * IP-01 primary label. It is derived ONLY from the trusted verified
     * sender-UUID -> handle mapping cached at acceptance, or the explicit
     * unverified label. A sender-chosen manifest name never appears here.
     */
    val primarySenderLabel: String,
    /** True only when [primarySenderLabel] came from the trusted mapping. */
    val senderIdentityVerified: Boolean,
    /** Sender-supplied manifest claim; rendered only as secondary text. */
    val claimedSenderHandle: String?,
    val yearAndDateLabel: String,
    val placeLabel: String?,
)

/** FSI/PDI first-strong isolation for user-controlled placeholder runs. */
internal fun bidiIsolated(value: String): String = "\u2068$value\u2069"

/**
 * IP-01 trusted-mapping policy: a sender-supplied name never becomes the
 * primary identity. Returns the trusted handle only when non-blank.
 */
internal fun chooserTrustedHandle(trustedSenderHandle: String?): String? =
    trustedSenderHandle?.takeIf { it.isNotBlank() }

/**
 * Primary sender identity comes only from the trusted mapping. When it is
 * absent, show the explicit unverified label instead of any sender-supplied
 * name. The user-controlled handle is bidi-isolated.
 */
@Composable
internal fun chooserPrimaryLabel(trustedSenderHandle: String?): String {
    val handle = chooserTrustedHandle(trustedSenderHandle)
    return if (handle == null) {
        stringResource(R.string.hold_chooser_unverified)
    } else {
        stringResource(R.string.hold_chooser_from, bidiIsolated("@$handle"))
    }
}

/** Secondary, clearly-marked claim text; null when the sender supplied none. */
@Composable
internal fun chooserClaimLabel(claimedSenderHandle: String?): String? {
    val claimed = claimedSenderHandle?.takeIf { it.isNotBlank() } ?: return null
    return stringResource(R.string.hold_chooser_claim, bidiIsolated(claimed))
}

/**
 * M1-M13 scan-scoped ambiguity chooser. Rows are ordered by score (the
 * ViewModel guarantees it) and reveal the minimum hints required to let the
 * recipient recognize their own postcard - nothing more.
 */
@Composable
fun AmbiguityChooserScreen(
    rows: List<ChooserHintRow>,
    onSelected: (ChooserHintRow) -> Unit,
    onRecapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Text(
            stringResource(R.string.hold_chooser_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("chooser_title"),
        )
        Spacer(Modifier.height(12.dp))
        rows.forEach { row ->
            Button(
                onClick = { onSelected(row) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chooser_row_${row.candidateId}"),
            ) {
                Column {
                    Text(
                        row.primarySenderLabel,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("chooser_primary_${row.candidateId}"),
                    )
                    chooserClaimLabel(row.claimedSenderHandle)?.let { claim ->
                        Text(
                            claim,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("chooser_claim_${row.candidateId}"),
                        )
                    }
                    Text(
                        row.yearAndDateLabel,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!row.placeLabel.isNullOrEmpty()) {
                        Text(
                            bidiIsolated(row.placeLabel),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("chooser_place_${row.candidateId}"),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = onRecapture,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chooser_recapture_button"),
        ) {
            Text(stringResource(R.string.hold_chooser_rescan))
        }
    }
}
