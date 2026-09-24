package dev.hryshyn.remanence.core.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-017/018 step 2: real G2 BER1 provider tests. */
class GeneratorBer1ProviderTest {

    private val provider = GeneratorBer1Provider.Ber1Provider()

    private fun photo(id: String, ordinal: Int, w: Int = 1000, h: Int = 1000) =
        GeneratorExpression.PhotoRef(
            contentId = id,
            ordinal = ordinal,
            widthPx = w,
            heightPx = h,
            contentHash = when (id) {
                "p1" -> "a".repeat(64)
                "p2" -> "b".repeat(64)
                else -> "c".repeat(64)
            },
        )

    private fun input(
        note: String? = null,
        music: GeneratorExpression.MusicRef? = null,
        dims: (String) -> Pair<Int, Int> = { 1000 to 1000 },
    ) = GeneratorExpression.GeneratorInput(
        ownerId = "owner-1",
        epoch = 7L,
        photos = listOf("p1", "p2", "p3").mapIndexed { index, id ->
            val (w, h) = dims(id)
            photo(id, index, w, h)
        },
        note = note,
        music = music,
    )

    private fun request(input: GeneratorExpression.GeneratorInput) =
        GeneratorDiscovery.GenerationRequest(input, "gen-1", 42L, 10)

    @Test
    fun emptyNoteYieldsOneDeterministicCandidate() = runTest {
        val first = provider.generate(request(input()))
        val second = provider.generate(request(input()))
        assertTrue(first is GeneratorDiscovery.ProviderOutcome.Candidates, "got $first")
        val candidate = (first as GeneratorDiscovery.ProviderOutcome.Candidates).candidates.single()
        assertEquals(0, candidate.ordinal)
        assertEquals(provider.ref, candidate.provider)
        assertTrue(candidate.candidateId.startsWith("ber1-"))
        assertTrue(candidate.candidateId.length <= 256)
        assertEquals(
            candidate.candidateId,
            (second as GeneratorDiscovery.ProviderOutcome.Candidates).candidates.single().candidateId,
        )
    }

    @Test
    fun nonEmptyNoteAndMusicRouteElsewhere() = runTest {
        assertTrue(
            provider.generate(request(input(note = "hi")))
                is GeneratorDiscovery.ProviderOutcome.NotApplicable,
        )
        assertTrue(
            provider.generate(request(input(music = GeneratorExpression.MusicRef("t", null))))
                is GeneratorDiscovery.ProviderOutcome.NotApplicable,
        )
    }

    @Test
    fun extremeAspectIncompatibleAndInvalidFails() = runTest {
        assertTrue(
            provider.generate(request(input(dims = { if (it == "p1") 2000 to 100 else 1000 to 1000 })))
                is GeneratorDiscovery.ProviderOutcome.Incompatible,
        )
        val twoPhotos = input().copy(photos = input().photos.take(2).mapIndexed { i, p -> p.copy(ordinal = i) })
        assertTrue(
            provider.generate(request(twoPhotos)) is GeneratorDiscovery.ProviderOutcome.Failed,
        )
    }

    @Test
    fun discoveryRunAcceptsTheBer1Candidate() = runTest {
        val result = GeneratorDiscovery.run(request(input()), listOf(provider))
        assertEquals(GeneratorDiscovery.GenerationTerminalOutcome.SUCCESS, result.terminal)
        assertEquals(1, result.accepted.size)
        assertEquals(provider.ref, result.accepted.single().provider)
    }

    @Test
    fun uriLikeContentIdsAreRejected() {
        val expression = input()
        val uriPhoto = expression.copy(
            photos = expression.photos.mapIndexed { index, p ->
                if (index == 0) p.copy(contentId = "content://media/external/images/1") else p
            },
        )
        assertTrue(
            GeneratorExpression.validate(uriPhoto) is GeneratorExpression.InputValidation.Invalid,
        )
        assertTrue(GeneratorExpression.looksLikeUriOrHandle("file:///tmp/x.jpg"))
        assertTrue(GeneratorExpression.looksLikeUriOrHandle("/abs/path"))
        assertTrue(!GeneratorExpression.looksLikeUriOrHandle("pv-0-abcd"))
    }
}
