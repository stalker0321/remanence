package dev.hryshyn.remanence.ui.capsule.music

import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import java.net.URI
import java.net.URLEncoder

/**
 * S3 bundled streaming services for the offline track card.
 *
 * Generic web SEARCH URLs only (no vendor API, auth, or exact-link
 * promise): the user taps a service and the track search opens in their
 * own browser/app. The query is always `title + artistDisplay` (the
 * version is omitted on purpose — version strings like "Live at the
 * Apollo" hurt search recall).
 *
 * Injection safety: user text is percent-encoded ([encodeQuery]) and can
 * only ever land inside the query/path of a fixed allowlisted HTTPS
 * origin. [isAllowedUrl] re-validates scheme/host/userinfo before any
 * intent fires. Pure JVM (no Android types) so the safety contract is
 * plain-unit-testable.
 */
enum class StreamingService(
    val host: String,
    val displayName: String,
    val testTag: String,
) {
    SPOTIFY(
        host = "open.spotify.com",
        displayName = "Spotify",
        testTag = "capsule_track_open_spotify",
    ),
    APPLE_MUSIC(
        host = "music.apple.com",
        displayName = "Apple Music",
        testTag = "capsule_track_open_apple",
    ),
    YOUTUBE_MUSIC(
        host = "music.youtube.com",
        displayName = "YouTube Music",
        testTag = "capsule_track_open_youtube",
    );

    /**
     * Builds the service search URL for [title]/[artistDisplay].
     * Throws [IllegalArgumentException] on blank or oversized input —
     * the sealed snapshot bounds already guarantee this, so a throw here
     * is defense in depth, never a user path.
     */
    fun searchUrl(title: String, artistDisplay: String): String {
        require(title.isNotBlank() && artistDisplay.isNotBlank()) { "track text must be non-blank" }
        val query = "$title $artistDisplay"
        require(query.length <= MAX_QUERY_CHARS) { "track query too long" }
        val encoded = encodeQuery(query)
        val url = when (this) {
            SPOTIFY -> "https://$host/search/$encoded"
            APPLE_MUSIC -> "https://$host/us/search?term=$encoded"
            YOUTUBE_MUSIC -> "https://$host/search?q=$encoded"
        }
        check(isAllowedUrl(url)) { "built URL failed the allowlist" }
        return url
    }

    companion object {
        /**
         * Exact fit for the largest sealed snapshot: 100-char title + one
         * space + 200-char artist display. Derived from the model bounds
         * (never a magic number) so a valid snapshot can never trip the
         * builder inside a Compose click handler.
         */
        const val MAX_QUERY_CHARS =
            CapsuleTrackSnapshotV1.MAX_TITLE_CHARS + 1 + CapsuleTrackSnapshotV1.MAX_ARTIST_DISPLAY_CHARS

        /**
         * Percent-encodes free text for a URL query or path segment.
         * [URLEncoder] emits `+` for spaces, which means a literal plus
         * inside a path — normalize to `%20`, valid in both positions.
         */
        fun encodeQuery(text: String): String =
            URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

        /** Strict allowlist: exact https host, no userinfo, no fragment. */
        fun isAllowedUrl(url: String): Boolean {
            val parsed = try {
                URI(url)
            } catch (_: Exception) {
                return false
            }
            if (!parsed.isAbsolute || parsed.scheme != "https") return false
            if (parsed.rawUserInfo != null || parsed.fragment != null) return false
            if (parsed.port != -1) return false
            return entries.any { it.host == parsed.host }
        }
    }
}
