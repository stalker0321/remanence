package dev.hryshyn.remanence.core.data.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import dev.hryshyn.remanence.core.model.GeneratorExpression

/**
 * Pure tests for the sender artwork fetch-time policy: documented hosts only,
 * the A4 sealable URL shape, and the "last stable hop" selection.
 *
 * No server, no client, no network — every case is a value in and a boolean
 * out, which is what makes the allow-list auditable.
 */
class MusicBrainzCoverArtPolicyTest {

    private val policy = MusicBrainzCoverArtHostPolicy

    @Test
    fun documentedMusicBrainzCaaAndArchiveHostsAreAllowed() {
        listOf(
            "https://musicbrainz.org/ws/2/recording/x",
            "https://coverartarchive.org/release-group/x/front-500",
            "https://archive.org/download/mbid-x/file.jpg",
            // Per-request archive nodes actually observed in the captured
            // protocol evidence (tasks/T062/evidence).
            "https://dn721806.ca.archive.org/0/items/mbid-x/file.jpg",
            "https://ia801506.us.archive.org/3/items/mbid-x/file.jpg",
        ).forEach { assertTrue(policy.allowsRedirect(it.toHttpUrl()), it) }
    }

    @Test
    fun foreignHostsSchemesAndUserinfoAreRejected() {
        listOf(
            "https://evil.example.invalid/steal.jpg",
            "https://archive.org.evil.invalid/download/x",
            "http://coverartarchive.org/release-group/x/front-500",
            "https://user:pass@coverartarchive.org/x",
            "https://127.0.0.1/x",
            "https://localhost/x",
        ).forEach { assertFalse(policy.allowsRedirect(it.toHttpUrl()), it) }
    }

    @Test
    fun theRemanenceBackendHostCanNeverBeAnArtworkHost() {
        // Contract T2: the fetch allow-list must never contain an ApiBaseUrl
        // host, so the backend cannot be asked to serve or proxy covers.
        val apiBaseUrl = ApiBaseUrl.parse("http://127.0.0.1:8000/").httpUrl
        assertFalse(policy.allowsRedirect(apiBaseUrl))
        assertFalse(policy.isCanonicalDownloadHost(apiBaseUrl))
        assertTrue(
            MusicBrainzCoverArtHostPolicy.HOST_SUFFIXES
                .none { it.contains("127.0.0.1") || it.contains("localhost") },
        )
    }

    @Test
    fun onlyTheBareArchiveDownloadHostCountsAsCanonical() {
        assertTrue(
            policy.isCanonicalDownloadHost("https://archive.org/download/mbid-1/a.jpg".toHttpUrl()),
        )
        // The node host that archive.org redirects to is NOT sealable: it
        // alternates between requests (captured evidence), so identity must
        // not be bound to it.
        assertFalse(
            policy.isCanonicalDownloadHost("https://dn721806.ca.archive.org/0/items/x/y.jpg".toHttpUrl()),
        )
        assertFalse(policy.isCanonicalDownloadHost("https://archive.org/details/x".toHttpUrl()))
        assertFalse(
            policy.isCanonicalDownloadHost("http://archive.org/download/x/y.jpg".toHttpUrl()),
        )
    }

    @Test
    fun sealableShapeMatchesRuleA4() {
        assertTrue(hasStableSealableShape("https://archive.org/download/mbid-1/a.jpg".toHttpUrl()))
        assertTrue(
            hasStableSealableShape(
                "https://coverartarchive.org/release-group/a29bc8d9-9b3e-3a13-98c9-257dd4020eb0/front-500"
                    .toHttpUrl(),
            ),
        )
        assertTrue(hasStableSealableShape("https://archive.org:443/download/x/y.jpg".toHttpUrl()))

        listOf(
            "http://archive.org/download/x/y.jpg", // scheme
            "https://archive.org/download/x/y.jpg?sig=abc", // signed/expiring query (T4)
            "https://archive.org/download/x/y.jpg#frag", // fragment
            "https://user@archive.org/download/x/y.jpg", // userinfo
            "https://archive.org:8080/download/x/y.jpg", // non-443 port
        ).forEach { assertFalse(hasStableSealableShape(it.toHttpUrl()), it) }

        val overlong = "https://archive.org/download/" + "a".repeat(600)
        assertFalse(hasStableSealableShape(overlong.toHttpUrl()))
    }

    @Test
    fun stableHopSelectionPrefersTheCanonicalDownloadTarget() {
        val caa = "https://coverartarchive.org/release-group/rg/front-500".toHttpUrl()
        val canonical = "https://archive.org/download/mbid-x/file_thumb500.jpg".toHttpUrl()
        val nodeA = "https://dn721806.ca.archive.org/0/items/mbid-x/file_thumb500.jpg".toHttpUrl()
        val nodeB = "https://ia801506.us.archive.org/3/items/mbid-x/file_thumb500.jpg".toHttpUrl()

        // Real captured chain: CAA -> archive.org/download -> node.
        assertEquals(
            canonical,
            MusicBrainzCoverArtResolver.stableSealableUrl(listOf(caa, canonical, nodeA)),
        )
        // A chain that never exposes the canonical hop cannot be sealed: the
        // node host alternates, so sealing it would bind identity to a host
        // the service re-picks on every request.
        assertNull(MusicBrainzCoverArtResolver.stableSealableUrl(listOf(caa, nodeA, nodeB)))
        // A single-hop answer (CAA served the bytes itself) seals its own URL.
        assertEquals(caa, MusicBrainzCoverArtResolver.stableSealableUrl(listOf(caa)))
    }

    @Test
    fun providerKeyIsAllowlistedAndNeverABackendHost() {
        val providers = GeneratorExpression.ARTWORK_PROVIDERS
        assertTrue("coverartarchive" in providers)
        assertTrue(providers.all { it.matches(Regex("[a-z0-9-]+")) })
        assertTrue(providers.none { it.contains("127.0.0.1") || it.contains("localhost") })
    }

    @Test
    fun sealedMimeSetAndResolverConstantsMatchTheContract() {
        assertEquals(
            setOf("image/jpeg", "image/png", "image/webp"),
            GeneratorExpression.ARTWORK_MIME_TYPES,
        )
        assertEquals("coverartarchive", MusicBrainzCoverArtResolver.COVER_ART_PROVIDER)
        assertEquals("front-500", MusicBrainzCoverArtResolver.COVER_THUMB)
        assertEquals(5, MusicBrainzCoverArtResolver.MAX_REDIRECTS)
        assertTrue(MusicBrainzCoverArtResolver.MAX_METADATA_BYTES < MusicBrainzCoverArtResolver.MAX_IMAGE_BYTES)
    }
}
