package dev.hryshyn.remanence.core.crypto

import com.google.crypto.tink.KeysetHandle
import dev.hryshyn.remanence.core.model.CapsuleTrackSnapshotV1
import dev.hryshyn.remanence.protocol.v1.PublishStatement
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * S2b-receiver accessor: the prepared material exposes the sealed snapshot
 * (or null) and refuses once closed. Construction here is direct — the
 * production path builds the same material through the acceptance gate,
 * already covered by the S2b-sender round-trip test.
 */
class PreparedPresentationMaterialTrackSnapshotTest {

    private lateinit var keyset: KeysetHandle

    private fun snapshot() = CapsuleTrackSnapshotV1.parse(
        trackId = "be30e36b-1111-4111-8111-000000000001",
        title = "505",
        artistDisplay = "Arctic Monkeys",
        version = null,
        durationMs = 253000L,
    )

    private fun material(snapshot: CapsuleTrackSnapshotV1?) = PreparedPresentationMaterial(
        statement = PublishStatement.getDefaultInstance(),
        capsuleKeyset = keyset,
        manifest = ContentManifestContent(
            protocolVersion = 2,
            photos = emptyList(),
            note = null,
            expression = null,
            trackSnapshot = snapshot,
        ),
        deliveredCiphertexts = emptyList(),
    )

    @BeforeTest
    fun setUp() {
        TinkPrimitives.ensureRegistered()
        keyset = CapsuleKeysetGenerator().generate()
    }

    @Test
    fun presentSnapshotExposed() {
        assertEquals(snapshot(), material(snapshot()).trackSnapshot())
    }

    @Test
    fun absentSnapshotExposesNull() {
        assertNull(material(null).trackSnapshot())
    }

    @Test
    fun closedMaterialRefuses() {
        val prepared = material(snapshot())
        prepared.close()
        assertFailsWith<IllegalStateException> { prepared.trackSnapshot() }
    }
}
