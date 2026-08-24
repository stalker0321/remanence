package app.postmark.memory.create

import postmark.core.crypto.EncryptedPhoto
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of assembling one capsule's photo set: encrypted artifacts only. */
class AssembledContent(
    val encryptedPhotos: List<EncryptedPhoto>,
    /** Plaintext note bytes are NOT retained here; the caller encrypts via manifest. */
    val noteUtf8Bytes: ByteArray?,
) {
    init {
        require(noteUtf8Bytes == null || noteUtf8Bytes.isNotEmpty()) { "note must be null or non-empty" }
    }
}

/**
 * I07: wires the Photo Picker selection (3-5), the bounded note, lazy staging
 * of picked photos, and sequential encryption into one flow with guaranteed
 * plaintext cleanup - staged plaintext files exist only inside [assemble] and
 * are deleted in a finally block before it returns, successful or not.
 */
class CapsuleContentAssembler(
    private val selection: app.postmark.memory.ui.create.PhotoSelectionState,
    private val noteEditor: app.postmark.memory.ui.create.NoteEditorState,
    private val stagingDirectory: File,
    private val normalizerPort: PhotoNormalizerPort,
    private val encryptorProvider: () -> postmark.core.crypto.PhotoArtifactEncryptor,
    private val capsuleKeysetProvider: () -> com.google.crypto.tink.KeysetHandle,
    private val routingContextProvider: () -> postmark.core.crypto.RecognitionManifestCodec.RoutingContext,
    private val openSourceFor: (pickerId: String) -> PhotoSource,
) {

    /**
     * Stages every selected photo one at a time, encrypts them sequentially,
     * and guarantees plaintext cleanup in `finally`. Throws when the picker
     * selection is outside 3..5 or the note exceeds its byte limit.
     */
    suspend fun assemble(): AssembledContent {
        val ids = selection.selectedIds
        require(ids.size in app.postmark.memory.ui.create.PhotoSelectionState.MIN_PHOTOS..
            app.postmark.memory.ui.create.PhotoSelectionState.MAX_PHOTOS) {
            "photo selection must hold 3..5 items"
        }
        require(noteEditor.canIncludeInCapsule) { "note exceeds byte limit" }

        val pipeline = PhotoStagingPipeline(stagingDirectory, normalizerPort)
        try {
            val staged = pipeline.stageAll(ids.map(openSourceFor))
            val batch = postmark.core.crypto.SequentialPhotoEncryptionBatch(encryptorProvider())
            val encrypted = batch.encryptInOrder(
                capsuleKeyset = capsuleKeysetProvider(),
                routingContext = routingContextProvider(),
                photos = staged.mapIndexed { index, photo ->
                    postmark.core.crypto.OrdinalPhoto(index) {
                        photo.file.inputStream()
                    }
                },
            )
            return AssembledContent(
                encryptedPhotos = encrypted,
                noteUtf8Bytes = if (noteEditor.isEmpty) null else noteEditor.text.toByteArray(Charsets.UTF_8),
            )
        } finally {
            withContext(Dispatchers.IO) {
                // Guaranteed plaintext cleanup: no staged JPEG survives this call.
                stagingDirectory.listFiles()?.forEach { it.delete() }
            }
        }
    }
}
