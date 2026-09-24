package dev.hryshyn.remanence.core.model

import java.nio.charset.Charset
import java.util.UUID

/**
 * S2 own-catalog track snapshot (sample slice, schema_version = 1).
 *
 * Immutable validated value bound at capsule creation from an
 * [S1-selected search hit][dev.hryshyn.remanence.core.data.network.MusicTrackHit];
 * the encrypted attachment itself lands in S2b. Independent of the
 * generator expression and hash-free: integrity comes from riding inside
 * the AEAD-sealed content manifest (proto field 7, v2-only). The frozen
 * `TrackAttachment` proto field 5 is never used.
 */
data class CapsuleTrackSnapshotV1 private constructor(
    val trackId: UUID,
    val title: String,
    val artistDisplay: String,
    val version: String?,
    val durationMs: Long?,
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_TITLE_CHARS = 100
        const val MAX_ARTIST_DISPLAY_CHARS = 200
        const val MAX_VERSION_CHARS = 100
        const val MAX_DURATION_MS = 86_400_000L

        private val UTF8: Charset = Charsets.UTF_8

        /**
         * Parses and strictly validates a snapshot; throws
         * [IllegalArgumentException] fail-closed on any violation (bad
         * UUID, blank/overlong text, non-UTF-8-encodable content,
         * non-positive or oversized duration).
         */
        fun parse(
            trackId: String,
            title: String,
            artistDisplay: String,
            version: String?,
            durationMs: Long?,
        ): CapsuleTrackSnapshotV1 {
            val id = try {
                UUID.fromString(trackId)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("snapshot track id is not a UUID")
            }
            require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS) {
                "snapshot title must be 1..$MAX_TITLE_CHARS chars"
            }
            require(artistDisplay.isNotBlank() && artistDisplay.length <= MAX_ARTIST_DISPLAY_CHARS) {
                "snapshot artist display must be 1..$MAX_ARTIST_DISPLAY_CHARS chars"
            }
            require(UTF8.newEncoder().canEncode(title)) { "snapshot title is not UTF-8 encodable" }
            require(UTF8.newEncoder().canEncode(artistDisplay)) { "snapshot artist display is not UTF-8 encodable" }
            version?.let {
                require(it.isNotBlank() && it.length <= MAX_VERSION_CHARS) {
                    "snapshot version must be 1..$MAX_VERSION_CHARS chars"
                }
                require(UTF8.newEncoder().canEncode(it)) { "snapshot version is not UTF-8 encodable" }
            }
            durationMs?.let {
                require(it in 1..MAX_DURATION_MS) { "snapshot duration must be 1..$MAX_DURATION_MS ms" }
            }
            return CapsuleTrackSnapshotV1(
                trackId = id,
                title = title,
                artistDisplay = artistDisplay,
                version = version,
                durationMs = durationMs,
            )
        }
    }

    override fun toString(): String =
        "CapsuleTrackSnapshotV1(trackId=$trackId, version=${version != null}, durationMs=${durationMs != null})"
}
