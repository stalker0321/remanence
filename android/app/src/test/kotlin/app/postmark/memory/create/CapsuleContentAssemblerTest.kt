package app.postmark.memory.create

import java.io.File
import java.nio.file.Files
import kotlin.io.path.walk
import kotlin.io.path.ExperimentalPathApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Lazy staging/encryption/cleanup proof for I07. */
@OptIn(ExperimentalPathApi::class)
class CapsuleContentAssemblerTest {

    private lateinit var stagingDir: File
    private val photoMarker = "plaintext-JPEG-canary"

    private fun selection(count: Int) = app.postmark.memory.ui.create.PhotoSelectionState().apply {
        (0 until count).forEach { toggle("id-$it") }
    }

    private fun noteEditor(text: String = "") = app.postmark.memory.ui.create.NoteEditorState().apply {
        onChange(text)
    }

    private fun unusedKeyset(): com.google.crypto.tink.KeysetHandle = error("unused keyset")
    private fun unusedRouting(): postmark.core.crypto.RecognitionManifestCodec.RoutingContext = error("unused routing")

    private fun assembler(
        state: app.postmark.memory.ui.create.PhotoSelectionState,
        encryptorProvider: () -> postmark.core.crypto.PhotoArtifactEncryptor = { postmark.core.crypto.PhotoArtifactEncryptor() },
    ) = CapsuleContentAssembler(
        selection = state,
        noteEditor = noteEditor(),
        stagingDirectory = stagingDir,
        normalizerPort = { input -> NormalizedPhotoDto(input.copyOf(), 800, 600) },
        encryptorProvider = encryptorProvider,
        capsuleKeysetProvider = ::unusedKeyset,
        routingContextProvider = ::unusedRouting,
        openSourceFor = { pickerId ->
            PhotoSource { "$photoMarker-for-$pickerId".toByteArray().inputStream() }
        },
    )

    @Before
    fun setUp() {
        stagingDir = Files.createTempDirectory("assembler-staging").toFile()
    }

    @Test
    fun undersizedSelectionIsRejectedBeforeAnyStaging() {
        // Two items: the picker state cannot even exceed five by construction.
        val flow = assembler(selection(2))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { flow.assemble() }
        }
        assertEquals("nothing staged", 0, stagingDir.listFiles()?.size ?: -1)
    }

    @Test
    fun pickerStateCapsSelectionAtFiveByConstruction() {
        val state = app.postmark.memory.ui.create.PhotoSelectionState()
        (0 until 8).forEach { index -> state.toggle("id-$index") }
        assertEquals(5, state.selectedIds.size)
        assertTrue(state.isAtLimit)
    }

    @Test
    fun stagedPlaintextFilesAreDeletedEvenWhenEncryptionFails() = runBlocking {
        val failing = CapsuleContentAssembler(
            selection = selection(3),
            noteEditor = noteEditor(),
            stagingDirectory = stagingDir,
            normalizerPort = { input -> NormalizedPhotoDto(input.copyOf(), 800, 600) },
            encryptorProvider = { postmark.core.crypto.PhotoArtifactEncryptor() },
            // Failure fires the moment encryption begins, after staging finished.
            capsuleKeysetProvider = { error("forced failure") },
            routingContextProvider = ::unusedRouting,
            openSourceFor = { id -> PhotoSource { "$photoMarker-$id".toByteArray().inputStream() } },
        )

        try {
            failing.assemble()
            throw AssertionError("expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("forced failure", expected.message)
        }

        assertEquals("no plaintext may survive", 0, stagingDir.listFiles()?.size ?: -1)
    }

    @Test
    fun noPlaintextBytesRemainAnywhereAfterAssemblyFailure() = runBlocking {
        val failing = CapsuleContentAssembler(
            selection = selection(3),
            noteEditor = noteEditor(),
            stagingDirectory = stagingDir,
            normalizerPort = { NormalizedPhotoDto(it.copyOf(), 10, 10) },
            encryptorProvider = { error("boom") },
            capsuleKeysetProvider = ::unusedKeyset,
            routingContextProvider = ::unusedRouting,
            openSourceFor = { id -> PhotoSource { "$photoMarker-$id".toByteArray().inputStream() } },
        )
        try {
            failing.assemble()
        } catch (_: IllegalStateException) {
        }
        assertTrue(
            stagingDir.walk().none { file ->
                file.isFile && String(file.readBytes()).contains(photoMarker)
            },
        )
        Unit
    }
}
