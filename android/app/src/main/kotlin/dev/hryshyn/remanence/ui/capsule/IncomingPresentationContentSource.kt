package dev.hryshyn.remanence.ui.capsule

import dev.hryshyn.remanence.core.crypto.ExpressionReceiverAdmission

/**
 * Reader over the exact snapshot retained by the incoming presentation
 * preparation gate. It performs no Room, filesystem, identity, or network
 * reread and does not own the handle; [PresentationGrantAuthority] does.
 */
internal class IncomingPresentationContentSource(
    private val prepared: PreparedIncomingPresentation,
) : CapsuleContentReader {
    override suspend fun photoCount(capsuleId: String): Int {
        require(capsuleId == prepared.capsuleId.toRestString()) { "capsule binding mismatch" }
        return prepared.photoCount
    }

    override suspend fun loadPhoto(capsuleId: String, ordinal: Int): DecryptedPhoto {
        require(capsuleId == prepared.capsuleId.toRestString()) { "capsule binding mismatch" }
        return DecryptedPhoto(ordinal, prepared.loadPhoto(ordinal))
    }

    override suspend fun noteText(capsuleId: String): String? {
        require(capsuleId == prepared.capsuleId.toRestString()) { "capsule binding mismatch" }
        return prepared.noteText()
    }

    override suspend fun presentationAdmission(capsuleId: String): CapsulePresentationAdmission {
        require(capsuleId == prepared.capsuleId.toRestString()) { "capsule binding mismatch" }
        if (prepared.protocolVersion == 1) return CapsulePresentationAdmission.LegacyV1
        return when (val admitted = prepared.expressionAdmission()) {
            is ExpressionReceiverAdmission.Result.Supported ->
                CapsulePresentationAdmission.Ber1(admitted.expression)
            is ExpressionReceiverAdmission.Result.Unsupported ->
                CapsulePresentationAdmission.Unsupported(admitted.reason)
        }
    }
}
