package app.postmark.memory.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.postmark.memory.capture.BackCaptureFlow
import app.postmark.memory.capture.FrontCaptureFlow
import app.postmark.memory.capture.FrontCaptureOutcome
import app.postmark.memory.capture.PreparedBackGate
import app.postmark.memory.capture.SingleStillCaptureShell
import app.postmark.memory.create.RealStillFingerprintProcessor
import app.postmark.memory.create.SameAccountCapsulePublisher
import app.postmark.memory.create.SameAccountCapsuleRequest
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.subtle.Base64
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import postmark.core.data.fingerprints.SealedFingerprintPersistence
import postmark.core.data.network.ResolvedHandleSnapshot
import postmark.core.data.outbox.CapsuleOutboxStager
import postmark.core.model.CapsuleId
import postmark.core.model.KeyBundleId
import postmark.core.model.UserId
import postmark.core.recognition.FingerprintSide
import postmark.core.recognition.QualityReason
import postmark.core.recognition.RecognitionProfile

/** One immutable snapshot of the local account used as sender AND recipient (M1). */
data class SenderIdentitySnapshot(
    val userId: String,
    val handle: String,
    val activeKeyBundleId: String,
    val encryptionPrivateHandle: KeysetHandle,
    val signingPrivateHandle: KeysetHandle,
)

/**
 * FIX-M1-007-11: the production Create flow over real components only -
 * directory resolve + explicit confirmation, front capture through the ORB
 * processor into sealed persistence, checklist-gated prepared back, Photo
 * Picker 3-5 plus bounded note, and ONE sealing path: the ciphertext-only
 * publisher feeding the durable outbox. There is no second, all-plaintext
 * route. Plaintext staging lives only inside [publish] and is cleared in a
 * finally-equivalent path; cancellation tears the session down.
 */
class CreateViewModel(
    private val directory: RecipientDirectoryPort,
    accessTokenProvider: () -> String?,
    private val identityProvider: suspend () -> SenderIdentitySnapshot?,
    private val persistence: SealedFingerprintPersistence,
    private val outboxStager: CapsuleOutboxStager,
    profile: RecognitionProfile,
    private val stagingDirectory: File,
    private val openPhotoSource: (pickerId: String) -> app.postmark.memory.create.PhotoSource,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    enum class Step { RECIPIENT_LOOKUP, RECIPIENT_CONFIRM, FRONT, BACK_CHECKLIST, BACK, CONTENT, PUBLISHING, PUBLISHED }

    val pickerVm = RecipientPickerViewModel(directory, accessTokenProvider, viewModelScope)

    private val sessionStore = CreateSessionStore()
    private val recipientFlow = CreateRecipientFlow(pickerVm, sessionStore)

    val confirmedRecipient: StateFlow<ResolvedHandleSnapshot?> get() = sessionStore.confirmedRecipient

    private val _step = MutableStateFlow(Step.RECIPIENT_LOOKUP)
    val step: StateFlow<Step> = _step.asStateFlow()

    /** Generated once per create session; binds captures and the outbox row. */
    private val _capsuleId: String = UUID.randomUUID().toString()
    val capsuleId: String get() = _capsuleId

    private val _qualityRejection = MutableStateFlow<Set<QualityReason>>(emptySet())
    val qualityRejection: StateFlow<Set<QualityReason>> = _qualityRejection.asStateFlow()

    private val _frontCaptured = MutableStateFlow(false)
    val frontCaptured: StateFlow<Boolean> = _frontCaptured.asStateFlow()

    private val _backCaptured = MutableStateFlow(false)
    val backCaptured: StateFlow<Boolean> = _backCaptured.asStateFlow()

    private val _publishError = MutableStateFlow<String?>(null)
    val publishError: StateFlow<String?> = _publishError.asStateFlow()

    // Content state.
    val photoSelection = PhotoSelectionState()
    val noteEditor = NoteEditorState()
    val backGate = PreparedBackGate()

    // Attempt-scoped processors; the UI supplies one fresh shell per attempt.
    private val frontProcessor = RealStillFingerprintProcessor(profile, FingerprintSide.FRONT)
    private val backProcessor = RealStillFingerprintProcessor(profile, FingerprintSide.BACK)

    private var frontFingerprintId: String? = null
    private var backFingerprintId: String? = null

    // ---------------------------------------------------------------------
    // Recipient steps.
    // ---------------------------------------------------------------------

    fun onHandleChange(value: String) = pickerVm.onHandleChange(value)

    fun lookupRecipient() = pickerVm.lookup()

    fun onResolved(snapshot: ResolvedHandleSnapshot) {
        recipientFlow.onResolved(snapshot)
        _step.value = Step.RECIPIENT_CONFIRM
    }

    fun confirmRecipient() {
        recipientFlow.onConfirm()
        _step.value = Step.FRONT
    }

    fun restartLookup() {
        recipientFlow.restartLookup()
        _step.value = Step.RECIPIENT_LOOKUP
    }

    // ---------------------------------------------------------------------
    // Capture steps.
    // ---------------------------------------------------------------------

    fun onFrontJpeg(jpegBytes: ByteArray, shell: SingleStillCaptureShell) {
        viewModelScope.launch {
            val flow = FrontCaptureFlow(shell, frontProcessor)
            handleFrontOutcome(flow.onJpegDelivered(jpegBytes, capsuleId, persistence))
        }
    }

    private suspend fun handleFrontOutcome(outcome: FrontCaptureOutcome) {
        when (outcome) {
            is FrontCaptureOutcome.Captured -> {
                frontFingerprintId = outcome.fingerprintId
                _qualityRejection.value = emptySet()
                _frontCaptured.value = true
                _step.value = Step.BACK_CHECKLIST
            }
            is FrontCaptureOutcome.QualityRejected -> _qualityRejection.value = outcome.reasons
            is FrontCaptureOutcome.Failed -> _publishError.value = outcome.message
        }
    }

    fun proceedToBackChecklist() {
        check(_frontCaptured.value) { "front must be captured first" }
        _step.value = Step.BACK_CHECKLIST
    }

    fun onBackJpeg(jpegBytes: ByteArray, shell: SingleStillCaptureShell) {
        viewModelScope.launch {
            val flow = BackCaptureFlow(backGate, backProcessor)
            handleBackOutcome(flow.onJpegDelivered(jpegBytes, capsuleId, persistence))
        }
    }

    private suspend fun handleBackOutcome(outcome: FrontCaptureOutcome) {
        when (outcome) {
            is FrontCaptureOutcome.Captured -> {
                backFingerprintId = outcome.fingerprintId
                _qualityRejection.value = emptySet()
                _backCaptured.value = true
                _step.value = Step.CONTENT
            }
            is FrontCaptureOutcome.QualityRejected -> _qualityRejection.value = outcome.reasons
            is FrontCaptureOutcome.Failed -> _publishError.value = outcome.message
        }
    }

    fun proceedToContent() {
        check(_backCaptured.value) { "back must be captured first" }
        _step.value = Step.CONTENT
    }

    // ---------------------------------------------------------------------
    // Sealing: the ONE production path - publisher → ciphertext outbox.
    // ---------------------------------------------------------------------

    fun startPublishing() {
        check(_frontCaptured.value && _backCaptured.value) { "both sides must be captured" }
        check(photoSelection.canProceed) { "3..5 photos required" }
        check(noteEditor.canIncludeInCapsule) { "note exceeds its byte limit" }
        _step.value = Step.PUBLISHING
        viewModelScope.launch { publish() }
    }

    private suspend fun publish() {
        _publishError.value = null
        val snapshot = confirmedRecipient.value
        if (snapshot == null) {
            failPublishing("recipient must be confirmed before publishing")
            return
        }
        val sender = identityProvider()
        if (sender == null) {
            failPublishing("local identity is unavailable; recovery required")
            return
        }
        if (snapshot.userId.value.toString() != sender.userId) {
            failPublishing("this milestone publishes only to your own account")
            return
        }
        val frontBytes = try {
            persistence.decrypt(requireNotNull(frontFingerprintId))
        } catch (_: Exception) {
            null
        }
        val backBytes = try {
            persistence.decrypt(requireNotNull(backFingerprintId))
        } catch (_: Exception) {
            null
        }
        if (frontBytes == null || backBytes == null) {
            failPublishing("sealed captures are unreadable; recapture required", restartAt = Step.FRONT)
            return
        }

        try {
            // Normalized plaintext photos exist ONLY inside this call; every
            // staged file is deleted before this method returns or throws.
            val pipeline = app.postmark.memory.create.PhotoStagingPipeline(
                stagingDirectory,
                normalizer = { jpeg ->
                    val normalized = postmark.core.recognition.PhotoNormalizer().normalize(jpeg)
                    app.postmark.memory.create.NormalizedPhotoDto(
                        normalized.jpegBytes,
                        normalized.width,
                        normalized.height,
                    )
                },
            )
            val staged = pipeline.stageAll(photoSelection.selectedIds.map(openPhotoSource))
            try {
                val prepared = SameAccountCapsulePublisher().publish(
                    SameAccountCapsuleRequest(
                        capsuleId = CapsuleId(UUID.fromString(capsuleId)),
                        senderUserId = UserId(UUID.fromString(sender.userId)),
                        senderKeyBundleId = KeyBundleId(UUID.fromString(sender.activeKeyBundleId)),
                        senderHandleSnapshot = sender.handle,
                        createdAtEpochSeconds = clockMillis() / 1000L,
                        photoJpegs = staged.map { it.file.readBytes() },
                        photoWidthsPx = staged.map { it.width },
                        photoHeightsPx = staged.map { it.height },
                        noteUtf8 = if (noteEditor.isEmpty) null else noteEditor.text,
                        frontFingerprintBytes = frontBytes,
                        backFingerprintBytes = backBytes,
                        signingKeyset = sender.signingPrivateHandle,
                        // M1 same-account: our own HPKE public half receives it.
                        recipientEncryptionPublicKeyset =
                            parsePublicHandle(snapshot.encryptionPublicKeysetB64Url),
                    ),
                )
                outboxStager.stage(prepared)
                _step.value = Step.PUBLISHED
            } finally {
                clearStagedPhotos()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _publishError.value = failure.message ?: "publishing failed"
            _step.value = Step.CONTENT
        }
    }

    private fun failPublishing(message: String, restartAt: Step = Step.CONTENT) {
        _publishError.value = message
        _step.value = restartAt
    }

    private fun clearStagedPhotos() {
        stagingDirectory.listFiles()?.forEach { it.delete() }
    }

    private fun parsePublicHandle(b64Url: String): KeysetHandle =
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(Base64.urlSafeDecode(b64Url))

    /** Leaving the create surface drops its transient session immediately. */
    fun endSession() {
        sessionStore.endSession()
        clearStagedPhotos()
    }

    override fun onCleared() {
        // Cancellation cleanup: transient session state never outlives the VM.
        endSession()
        super.onCleared()
    }
}
