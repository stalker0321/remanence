package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * Sender-side music artwork descriptor source (T062).
 *
 * Turns the canonical recording MBID carried on a search hit into a typed
 * outcome. `Resolved` carries the sealable descriptor plus the exact
 * already-fetched, verified image bytes needed for preview decoding;
 * `Unavailable` carries one bounded public reason. Absence never blocks the
 * music flow and never fabricates a provider.
 *
 * Implementations talk directly to the music provider; the Remanence backend
 * is never asked for bytes or URLs (V5 invariant I1).
 */
fun interface MusicArtworkResolver {
    /** Typed resolution outcome for [recordingMbid]; never throws. */
    suspend fun resolveOutcome(recordingMbid: String?): MusicArtworkResolution
}

/**
 * Public, privacy-safe artwork resolution reason. Maps 1:1 from
 * [CoverArtResolution.Reason]; `RESOLVED` marks success. The name is the
 * only diagnostic token ever emitted — never a URL, digest, byte count,
 * title, MBID, account/capsule identity or exception text.
 */
enum class MusicArtworkReason {
    RESOLVED,
    NO_RECORDING_ID,
    NO_RELEASE_GROUP,
    NO_COVER,
    HTTP_ERROR,
    HOST_NOT_ALLOWED,
    OVERSIZED,
    UNSUPPORTED_MEDIA_TYPE,
    DECODE_FAILED,
    STABLE_URL_UNAVAILABLE,
    INVALID_METADATA,
    NETWORK,
}

/** Typed artwork resolution outcome; the lossy `ArtworkRef?` seam replaced. */
sealed interface MusicArtworkResolution {
    /** Validated descriptor plus the exact fetched, verified image bytes. */
    data class Resolved(
        val artwork: GeneratorExpression.ArtworkRef,
        val bytes: ByteArray,
    ) : MusicArtworkResolution

    /** Bounded public reason; no request may have been made. */
    data class Unavailable(val reason: MusicArtworkReason) : MusicArtworkResolution
}

/** 1:1 public mapping of the resolver's internal failure reason. */
fun CoverArtResolution.Reason.toArtworkReason(): MusicArtworkReason = when (this) {
    CoverArtResolution.Reason.NO_RECORDING_ID -> MusicArtworkReason.NO_RECORDING_ID
    CoverArtResolution.Reason.NO_RELEASE_GROUP -> MusicArtworkReason.NO_RELEASE_GROUP
    CoverArtResolution.Reason.NO_COVER -> MusicArtworkReason.NO_COVER
    CoverArtResolution.Reason.HTTP_ERROR -> MusicArtworkReason.HTTP_ERROR
    CoverArtResolution.Reason.HOST_NOT_ALLOWED -> MusicArtworkReason.HOST_NOT_ALLOWED
    CoverArtResolution.Reason.OVERSIZED -> MusicArtworkReason.OVERSIZED
    CoverArtResolution.Reason.UNSUPPORTED_MEDIA_TYPE -> MusicArtworkReason.UNSUPPORTED_MEDIA_TYPE
    CoverArtResolution.Reason.DECODE_FAILED -> MusicArtworkReason.DECODE_FAILED
    CoverArtResolution.Reason.STABLE_URL_UNAVAILABLE -> MusicArtworkReason.STABLE_URL_UNAVAILABLE
    CoverArtResolution.Reason.INVALID_METADATA -> MusicArtworkReason.INVALID_METADATA
    CoverArtResolution.Reason.NETWORK -> MusicArtworkReason.NETWORK
}

/**
 * Bounded DEBUG diagnostic for one completed resolution. The returned string
 * is exactly `reason=<NAME>` — the allowlist is the enum name set, so no
 * identifier, URL, digest, byte content or exception text can pass through.
 */
fun artworkResolveDiagnostic(reason: MusicArtworkReason): String = "reason=${reason.name}"

/** Production adapter: typed outcome with the verified bytes retained. */
fun MusicBrainzCoverArtResolver.asMusicArtworkResolver(): MusicArtworkResolver =
    MusicArtworkResolver { recordingMbid ->
        when (val outcome = resolve(recordingMbid)) {
            is CoverArtResolution.Resolved ->
                MusicArtworkResolution.Resolved(outcome.artwork, outcome.bytes.copyOf())
            is CoverArtResolution.Unavailable ->
                MusicArtworkResolution.Unavailable(outcome.reason.toArtworkReason())
        }
    }
