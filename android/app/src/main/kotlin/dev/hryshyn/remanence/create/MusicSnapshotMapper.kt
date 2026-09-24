package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.core.data.network.MusicTrackHit
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1

/**
 * S2b-sender: maps the picker selection into the sealed snapshot.
 *
 * Throws [IllegalArgumentException] fail-closed on any invalid hit; the
 * caller ([CreateViewModel.publish]) converts that into a publish failure,
 * never a silent drop and never an unsealed attachment. The artist display
 * joins all credited artists (", " separator); overlong joins are rejected
 * by the snapshot bounds.
 */
internal fun mapMusicSelectionToSnapshot(selection: MusicTrackHit): CapsuleTrackSnapshotV1 =
    CapsuleTrackSnapshotV1.parse(
        trackId = selection.id,
        title = selection.title,
        artistDisplay = selection.artists.joinToString(", "),
        version = selection.version,
        durationMs = selection.durationMs,
    )
