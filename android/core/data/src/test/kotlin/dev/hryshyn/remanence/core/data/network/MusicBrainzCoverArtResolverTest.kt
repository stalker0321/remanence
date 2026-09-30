package dev.hryshyn.remanence.core.data.network

import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import dev.hryshyn.remanence.core.model.GeneratorExpression
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/**
 * Hermetic client tests for the sender-side MusicBrainz/CAA resolver.
 *
 * Everything runs against `MockWebServer` on loopback: no test in this file
 * touches the network. The bounds reader is a fake, so "image" bodies are
 * opaque payloads — decode policy is asserted through the reader result, not
 * through a real decoder. The strict production URL-shape rule is asserted in
 * [MusicBrainzCoverArtPolicyTest]; here it is exercised once over loopback
 * `http` to prove the shipped default really rejects an unsealable URL.
 */
class MusicBrainzCoverArtResolverTest {

    private lateinit var server: MockWebServer

    private val coverBody = "fake-cover-payload-not-a-real-image"

    private val coverSha256: ByteArray =
        MessageDigest.getInstance("SHA-256").digest(coverBody.toByteArray())

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    // -- happy path ----------------------------------------------------------

    @Test
    fun resolvesSealedDescriptorFromRecordingMetadataAndCoverBytes() = runTest {
        server.enqueue(json(recordingJson()))
        server.enqueue(image())

        val outcome = resolver(sealShape = { true }).resolve(RECORDING_MBID)

        val resolved = assertIs<CoverArtResolution.Resolved>(outcome)
        val artwork = resolved.artwork
        assertEquals(MusicBrainzCoverArtResolver.COVER_ART_PROVIDER, artwork.provider)
        assertEquals(
            server.url("/release-group/$RELEASE_GROUP/front-500").toString(),
            artwork.url,
        )
        assertEquals("image/jpeg", artwork.mimeType)
        assertEquals(500, artwork.width)
        assertEquals(500, artwork.height)
        assertTrue(coverSha256.contentEquals(artwork.sha256))
        // Rule A4 apart from the injected loopback scheme: never sealed with a
        // query, a fragment or userinfo.
        assertTrue('?' !in artwork.url && '#' !in artwork.url && '@' !in artwork.url)

        val metadata = server.takeRequest()
        assertEquals("/ws/2/recording/$RECORDING_MBID", metadata.url.encodedPath)
        assertEquals("releases+release-groups", metadata.url.queryParameter("inc"))
        assertEquals("json", metadata.url.queryParameter("fmt"))
        assertNull(metadata.headers["Authorization"])

        val cover = server.takeRequest()
        assertEquals("/release-group/$RELEASE_GROUP/front-500", cover.url.encodedPath)
        assertNull(cover.url.query)
        assertNull(cover.headers["Authorization"])
    }

    @Test
    fun shippedShapeRuleRejectsAPlainHttpSealTarget() = runTest {
        server.enqueue(json(recordingJson()))
        server.enqueue(image())

        // Identical transport to the happy path, but the production
        // `hasStableSealableShape` predicate: loopback http can never be sealed.
        val outcome = resolver().resolve(RECORDING_MBID)

        assertEquals(
            CoverArtResolution.Unavailable(CoverArtResolution.Reason.STABLE_URL_UNAVAILABLE),
            outcome,
        )
    }

    // -- metadata guards -----------------------------------------------------

    @Test
    fun missingOrMalformedRecordingMbidNeverIssuesARequest() = runTest {
        for (mbid in listOf(null, "", "   ", "not-a-uuid", "46FE768C-7B38-4147-9F02-815B9F0759E2")) {
            val outcome = resolver(sealShape = { true }).resolve(mbid)
            assertEquals(
                CoverArtResolution.Unavailable(CoverArtResolution.Reason.NO_RECORDING_ID),
                outcome,
                "expected NO_RECORDING_ID for $mbid",
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun metadataWithoutAUsableReleaseGroupYieldsNoReleaseGroup() = runTest {
        server.enqueue(json("""{"releases":[{"title":"no group"}]}"""))

        val outcome = resolver(sealShape = { true }).resolve(RECORDING_MBID)

        assertEquals(
            CoverArtResolution.Unavailable(CoverArtResolution.Reason.NO_RELEASE_GROUP),
            outcome,
        )
    }

    @Test
    fun nonCanonicalReleaseGroupIdInMetadataIsIgnored() = runTest {
        server.enqueue(
            json("""{"releases":[{"date":"1991-01-01","release-group":{"id":"UPPER-CASE"}}]}"""),
        )

        val outcome = resolver(sealShape = { true }).resolve(RECORDING_MBID)

        assertEquals(
            CoverArtResolution.Unavailable(CoverArtResolution.Reason.NO_RELEASE_GROUP),
            outcome,
        )
    }

    @Test
    fun oversizeMetadataBodyIsRejectedBeforeParsing() = runTest {
        server.enqueue(json("x".repeat(MusicBrainzCoverArtResolver.MAX_METADATA_BYTES + 1)))

        val outcome = resolver(sealShape = { true }).resolve(RECORDING_MBID)

        assertEquals(
            CoverArtResolution.Unavailable(CoverArtResolution.Reason.OVERSIZED),
            outcome,
        )
    }

    // -- cover guards --------------------------------------------------------

    @Test
    fun coverNotFoundYieldsNoCover() = runTest {
        server.enqueue(json(recordingJson()))
        server.enqueue(MockResponse.Builder().code(404).build())

        val outcome = resolver(sealShape = { true }).resolve(RECORDING_MBID)

        assertEquals(
            CoverArtResolution.Unavailable(CoverArtResolution.Reason.NO_COVER),
            outcome,
        )
    }

    @Test
    fun coverServerErrorYieldsHttpError() = runTest {
        server.enqueue(json(recordingJson()))
        server.enqueue(MockResponse.Builder().code(500).build())

        val outcome = resolver(sealShape = { true }).resolve(RECORDING_MBID)

        assertEquals(
            CoverArtResolution.Unavailable(CoverArtResolution.Reason.HTTP_ERROR),
            outcome,
        )
    }

    @Test
    fun nonImageMediaTypeIsRejected() = runTest {
        server.enqueue(json(recordingJson()))
        server.enqueue(image(contentType = "text/html"))

        val outcome = resolver(sealShape = { true }).resolve(RECORDING_MBID)

        assertEquals(
            CoverArtResolution.Unavailable(CoverArtResolution.Reason.UNSUPPORTED_MEDIA_TYPE),
            outcome,
        )
    }

    @Test
    fun undecodableOrOutOfRangeBoundsAreRejected() = runTest {
        server.enqueue(json(recordingJson()))
        server.enqueue(image())
        assertEquals(
            CoverArtResolution.Unavailable(CoverArtResolution.Reason.DECODE_FAILED),
            resolver(sealShape = { true }, bounds = ImageBoundsReader { null })
                .resolve(RECORDING_MBID),
        )

        server.enqueue(json(recordingJson()))
        server.enqueue(image())
        assertEquals(
            CoverArtResolution.Unavailable(CoverArtResolution.Reason.DECODE_FAILED),
            resolver(
                sealShape = { true },
                bounds = ImageBoundsReader { ImageSize(GeneratorExpression.ARTWORK_DIM_MAX + 1, 10) },
            ).resolve(RECORDING_MBID),
        )
    }

    @Test
    fun redirectLeavingTheAllowedHostIsRejectedWithoutFollowing() = runTest {
        server.enqueue(json(recordingJson()))
        server.enqueue(
            MockResponse.Builder()
                .code(307)
                .setHeader("Location", "https://evil.example.invalid/steal.jpg")
                .build(),
        )

        val outcome = resolver(sealShape = { true }).resolve(RECORDING_MBID)

        assertEquals(
            CoverArtResolution.Unavailable(CoverArtResolution.Reason.HOST_NOT_ALLOWED),
            outcome,
        )
        // Two requests only: the foreign hop was never fetched.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRedirectCarryingAQueryIsRejectedAsUnsealable() = runTest {
        server.enqueue(json(recordingJson()))
        server.enqueue(
            MockResponse.Builder()
                .code(307)
                .setHeader("Location", server.url("/cover.jpg?token=expiring").toString())
                .build(),
        )

        val outcome = resolver(sealShape = { true }).resolve(RECORDING_MBID)

        assertEquals(
            CoverArtResolution.Unavailable(CoverArtResolution.Reason.HOST_NOT_ALLOWED),
            outcome,
        )
        assertEquals(2, server.requestCount)
    }

    // -- determinism ---------------------------------------------------------

    @Test
    fun releaseGroupChoiceIsDeterministicAcrossDateAndIdOrdering() = runTest {
        val resolver = resolver()
        val late = ReleaseDto("2001-05-06", ReleaseGroupDto("11111111-1111-4111-8111-111111111111"))
        val early = ReleaseDto("1991-01-02", ReleaseGroupDto("22222222-2222-4222-8222-222222222222"))
        val undated = ReleaseDto(null, ReleaseGroupDto("33333333-3333-4333-8333-333333333333"))
        val noGroup = ReleaseDto("1980-01-01", null)

        val pick = resolver.pickReleaseGroupId(
            RecordingLookupDto(listOf(late, undated, noGroup, early)),
        )
        assertEquals("22222222-2222-4222-8222-222222222222", pick)

        val reordered = resolver.pickReleaseGroupId(
            RecordingLookupDto(listOf(early, noGroup, late, undated)),
        )
        assertEquals(pick, reordered)

        // Undated sorts last; the earliest known date wins.
        assertEquals(
            "11111111-1111-4111-8111-111111111111",
            resolver.pickReleaseGroupId(RecordingLookupDto(listOf(undated, late))),
        )
    }

    // -- helpers -------------------------------------------------------------

    private fun resolver(
        sealShape: (HttpUrl) -> Boolean = ::hasStableSealableShape,
        bounds: ImageBoundsReader = ImageBoundsReader { ImageSize(500, 500) },
    ): MusicBrainzCoverArtResolver = MusicBrainzCoverArtResolver(
        client = HttpClientFactory.create(),
        boundsReader = bounds,
        musicBrainzBaseUrl = server.url("/"),
        coverArtBaseUrl = server.url("/"),
        hostPolicy = object : CoverArtHostPolicy {
            override fun allowsRedirect(url: HttpUrl): Boolean = url.host == server.url("/").host
            override fun isCanonicalDownloadHost(url: HttpUrl): Boolean = false
        },
        sealShapeCheck = sealShape,
    )

    private fun json(body: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun image(contentType: String = "image/jpeg"): MockResponse =
        MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", contentType)
            .body(coverBody)
            .build()

    /** Earliest release date belongs to [RELEASE_GROUP]; it must win. */
    private fun recordingJson(): String = """
        {"releases":[
          {"date":"2004-03-15","release-group":{"id":"$OTHER_RELEASE_GROUP"}},
          {"date":"1991-06-04","release-group":{"id":"$RELEASE_GROUP"}}
        ]}
    """.trimIndent()

    private companion object {
        const val RECORDING_MBID = "46fe768c-7b38-4147-9f02-815b9f0759e2"
        const val RELEASE_GROUP = "a29bc8d9-9b3e-3a13-98c9-257dd4020eb0"
        const val OTHER_RELEASE_GROUP = "99999999-9999-4999-8999-999999999999"
    }
}
