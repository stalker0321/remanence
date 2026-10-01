package dev.hryshyn.remanence.core.data.network

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/**
 * Hermetic tests for the typed T144 artwork outcome seam.
 *
 * Everything runs against `MockWebServer` on loopback: no test in this file
 * touches the network. Image bodies are opaque payloads; the bounds reader
 * is a fake, exactly as in [MusicBrainzCoverArtResolverTest].
 */
class MusicArtworkResolverTest {

    private lateinit var server: MockWebServer

    private val coverBytes = "fake-cover-payload-not-a-real-image".toByteArray()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    @Test
    fun resolvedOutcomeCarriesExactFetchedBytesAndDescriptor() = runTest {
        server.enqueue(json(recordingJson()))
        server.enqueue(image())

        val outcome = adapter().resolveOutcome(RECORDING_MBID)

        val resolved = assertIs<MusicArtworkResolution.Resolved>(outcome)
        assertTrue(coverBytes.contentEquals(resolved.bytes))
        assertEquals(MusicBrainzCoverArtResolver.COVER_ART_PROVIDER, resolved.artwork.provider)
        assertEquals("image/jpeg", resolved.artwork.mimeType)
        assertEquals(500, resolved.artwork.width)
        assertEquals(500, resolved.artwork.height)
        assertEquals(
            server.url("/release-group/$RELEASE_GROUP/front-500").toString(),
            resolved.artwork.url,
        )
    }

    @Test
    fun everyInternalReasonMapsToMatchingPublicReason() {
        for (reason in CoverArtResolution.Reason.values()) {
            assertEquals(reason.name, reason.toArtworkReason().name)
        }
        // The public enum is exactly the internal set plus RESOLVED.
        assertEquals(
            CoverArtResolution.Reason.values().size + 1,
            MusicArtworkReason.values().size,
        )
    }

    @Test
    fun nullMbidMapsToNoRecordingIdWithZeroRequests() = runTest {
        val outcome = adapter().resolveOutcome(null)

        assertEquals(
            MusicArtworkResolution.Unavailable(MusicArtworkReason.NO_RECORDING_ID),
            outcome,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun blankMbidMapsToNoRecordingIdWithZeroRequests() = runTest {
        val outcome = adapter().resolveOutcome("   ")

        assertEquals(
            MusicArtworkResolution.Unavailable(MusicArtworkReason.NO_RECORDING_ID),
            outcome,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun failedFetchMapsToTheMappedPublicReason() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        val outcome = adapter().resolveOutcome(RECORDING_MBID)

        assertEquals(
            MusicArtworkResolution.Unavailable(MusicArtworkReason.NO_RELEASE_GROUP),
            outcome,
        )
    }

    @Test
    fun diagnosticStringIsExactlyTheReasonToken() {
        for (reason in MusicArtworkReason.values()) {
            assertEquals("reason=${reason.name}", artworkResolveDiagnostic(reason))
        }
        // Allowlist shape: nothing but the token can pass through.
        assertTrue(artworkResolveDiagnostic(MusicArtworkReason.RESOLVED).matches(TOKEN))
        assertTrue("https://example.invalid/x" !in artworkResolveDiagnostic(MusicArtworkReason.NO_COVER))
    }

    private fun adapter(): MusicArtworkResolver =
        MusicBrainzCoverArtResolver(
            client = HttpClientFactory.create(),
            boundsReader = ImageBoundsReader { ImageSize(500, 500) },
            musicBrainzBaseUrl = server.url("/"),
            coverArtBaseUrl = server.url("/"),
            hostPolicy = object : CoverArtHostPolicy {
                override fun allowsRedirect(url: okhttp3.HttpUrl): Boolean =
                    url.host == server.url("/").host
                override fun isCanonicalDownloadHost(url: okhttp3.HttpUrl): Boolean = false
            },
            sealShapeCheck = { true },
        ).asMusicArtworkResolver()

    private fun json(body: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun image(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "image/jpeg")
            .body(coverBytes.decodeToString())
            .build()

    private fun recordingJson(): String = """
        {"releases":[
          {"date":"1991-06-04","release-group":{"id":"$RELEASE_GROUP"}}
        ]}
    """.trimIndent()

    private companion object {
        const val RECORDING_MBID = "46fe768c-7b38-4147-9f02-815b9f0759e2"
        const val RELEASE_GROUP = "a29bc8d9-9b3e-3a13-98c9-257dd4020eb0"
        val TOKEN = Regex("^reason=[A-Z_]+$")
    }
}
