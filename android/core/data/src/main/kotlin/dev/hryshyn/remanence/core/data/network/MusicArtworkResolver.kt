package dev.hryshyn.remanence.core.data.network

import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * Sender-side music artwork descriptor source (T062).
 *
 * Turns the canonical recording MBID carried on a search hit into a descriptor
 * that can be sealed into the expression. Returning `null` means "no artwork"
 * and the capsule keeps `mode = "text"` — absence never blocks the music flow
 * and never fabricates a provider.
 *
 * Implementations talk directly to the music provider; the Remanence backend
 * is never asked for bytes or URLs (V5 invariant I1).
 */
fun interface MusicArtworkResolver {
    /** Sealable descriptor for [recordingMbid], or `null` to degrade to text. */
    suspend fun resolve(recordingMbid: String?): GeneratorExpression.ArtworkRef?
}

/** Production adapter: any provider/protocol outcome degrades to text mode. */
fun MusicBrainzCoverArtResolver.asMusicArtworkResolver(): MusicArtworkResolver =
    MusicArtworkResolver { recordingMbid ->
        when (val outcome = resolve(recordingMbid)) {
            is CoverArtResolution.Resolved -> outcome.artwork
            is CoverArtResolution.Unavailable -> null
        }
    }

/** Absent provider: every selection seals as text, exactly as before T062. */
object NoOpMusicArtworkResolver : MusicArtworkResolver {
    override suspend fun resolve(recordingMbid: String?): GeneratorExpression.ArtworkRef? = null
}
