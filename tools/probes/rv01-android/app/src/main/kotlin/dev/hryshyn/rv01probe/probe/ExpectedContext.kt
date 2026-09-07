package dev.hryshyn.rv01probe.probe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class AccountBindingClass(val wireValue: Int) {
    A(0),
    B(1),
    ;

    companion object {
        fun fromWireValue(value: Int): AccountBindingClass? =
            values().firstOrNull { it.wireValue == value }
    }
}

enum class ContextProfile(val wireValue: Int) {
    BLOCKSTORE_U_PLUS_P(1),
    ;

    companion object {
        fun fromWireValue(value: Int): ContextProfile? =
            values().firstOrNull { it.wireValue == value }
    }
}

enum class ContextPurpose {
    BLOCKSTORE_REC01,
}

enum class ContextGeneration(val wireValue: Long) {
    G1(1L),
    G2(2L),
    ;

    companion object {
        fun fromWireValue(value: Long): ContextGeneration? =
            values().firstOrNull { it.wireValue == value }
    }
}

enum class ContextTargetRole(val wireValue: Int) {
    D1_SOURCE(1),
    D2_TARGET(2),
    ;

    companion object {
        fun fromWireValue(value: Int): ContextTargetRole? =
            values().firstOrNull { it.wireValue == value }
    }
}

/**
 * Probe-only trusted context. It is supplied independently of sidecar P.
 * This is not an account identity or a Remanence recovery context.
 */
data class ExpectedContext(
    val accountBindingClass: AccountBindingClass,
    val profile: ContextProfile = ContextProfile.BLOCKSTORE_U_PLUS_P,
    val profileVersion: Int = 1,
    val purpose: ContextPurpose = ContextPurpose.BLOCKSTORE_REC01,
    val runId: ByteArray,
    val generation: ContextGeneration,
    val targetRole: ContextTargetRole,
) {
    init {
        require(runId.size == RUN_ID_BYTES) { "runId must be exactly 16 bytes" }
        require(profileVersion == 1) { "only context profile version 1 is supported" }
    }

    /** Canonical ContextV1 bytes, exactly 50 bytes, big-endian. */
    fun canonicalBytes(): ByteArray = ByteBuffer.allocate(CANONICAL_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .apply {
            putInt(MAGIC)
            put(accountBindingClass.wireValue.toByte())
            put(profile.wireValue.toByte())
            putShort(profileVersion.toShort())
            put(PURPOSE_BYTES.size.toByte())
            put(PURPOSE_BYTES)
            put(runId)
            putLong(generation.wireValue)
            put(targetRole.wireValue.toByte())
        }
        .array()

    internal fun wipeRunId() {
        runId.fill(0)
    }

    override fun equals(other: Any?): Boolean =
        other is ExpectedContext &&
            accountBindingClass == other.accountBindingClass &&
            profile == other.profile &&
            profileVersion == other.profileVersion &&
            purpose == other.purpose &&
            runId.contentEquals(other.runId) &&
            generation == other.generation &&
            targetRole == other.targetRole

    override fun hashCode(): Int {
        var result = accountBindingClass.hashCode()
        result = 31 * result + profile.hashCode()
        result = 31 * result + profileVersion
        result = 31 * result + purpose.hashCode()
        result = 31 * result + runId.contentHashCode()
        result = 31 * result + generation.hashCode()
        result = 31 * result + targetRole.hashCode()
        return result
    }

    companion object {
        const val CANONICAL_BYTES = 50
        const val RUN_ID_BYTES = 16
        private const val MAGIC = 0x45433031 // ASCII EC01
        private const val PROFILE_VERSION = 1
        private const val PURPOSE = "BLOCKSTORE_REC01"
        private val PURPOSE_BYTES = PURPOSE.toByteArray(StandardCharsets.UTF_8)

        /** Strictly decodes canonical ContextV1 bytes; malformed input returns null. */
        fun decode(encoded: ByteArray): ExpectedContext? {
            if (encoded.size != CANONICAL_BYTES) return null

            var purposeBytes: ByteArray? = null
            var decodedRunId: ByteArray? = null
            var result: ExpectedContext? = null
            try {
                val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
                if (buffer.int != MAGIC) return null

                val account = AccountBindingClass.fromWireValue(buffer.get().toInt())
                    ?: return null
                val profile = ContextProfile.fromWireValue(buffer.get().toInt())
                    ?: return null
                val profileVersion = buffer.short.toInt() and 0xffff
                if (profileVersion != PROFILE_VERSION) return null

                val purposeLength = buffer.get().toInt() and 0xff
                if (purposeLength != PURPOSE_BYTES.size) return null
                purposeBytes = ByteArray(purposeLength).also(buffer::get)
                if (!isStrictPurpose(purposeBytes)) return null

                decodedRunId = ByteArray(RUN_ID_BYTES).also(buffer::get)
                val generation = ContextGeneration.fromWireValue(buffer.long)
                    ?: return null
                val targetRole = ContextTargetRole.fromWireValue(buffer.get().toInt())
                    ?: return null
                if (buffer.hasRemaining()) return null

                result = ExpectedContext(
                    accountBindingClass = account,
                    profile = profile,
                    profileVersion = profileVersion,
                    purpose = ContextPurpose.BLOCKSTORE_REC01,
                    runId = decodedRunId,
                    generation = generation,
                    targetRole = targetRole,
                )
                return result
            } catch (_: RuntimeException) {
                return null
            } finally {
                purposeBytes?.fill(0)
                if (result == null) decodedRunId?.fill(0)
            }
        }

        /** Validates the fixed layout without accepting any sidecar authority. */
        internal fun isCanonical(encoded: ByteArray): Boolean {
            val decoded = decode(encoded) ?: return false
            val canonical = decoded.canonicalBytes()
            return try {
                encoded.contentEquals(canonical)
            } finally {
                canonical.fill(0)
                decoded.wipeRunId()
            }
        }

        private fun isStrictPurpose(bytes: ByteArray): Boolean = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString() == PURPOSE
        } catch (_: CharacterCodingException) {
            false
        }
    }
}
