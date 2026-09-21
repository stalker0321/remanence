package dev.hryshyn.remanence.create

import dev.hryshyn.remanence.core.crypto.PhotoArtifactEncryptor
import dev.hryshyn.remanence.core.crypto.readBoundedBytes
import dev.hryshyn.remanence.core.model.GeneratorDiscovery
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.ProtocolV1Limits
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * G4-B — client-owned source adapter/normalization binding: adapts lazy
 * [PhotoSource] handles sequentially one at a time into hash-bound,
 * upright-verified, normalized originals ready for G3 staging.
 *
 * Authority: `docs/hold/generator-boundary.md` (bounded read handles;
 * original dimensions; no fake content), arch §2/§8, and the rulings
 * that staged identity rests on ORIGINAL bytes→hash while
 * normalization/decoding are derived operations.
 *
 * Scope (strict): bounded original read (≤32 MiB), SHA-256 verification
 * against the G1 `contentHash` BEFORE decode, EXIF-upright decode via
 * [PhotoDecoderPort], upright-dimension match against declared G1 dims,
 * deterministic normalization through the trusted
 * [PhotoNormalizerPort] with the ≤8 MiB derived budget, explicit
 * original-vs-normalized distinction (normalized bytes copied on
 * retain; derived SHA-256 + strictly-positive policy versions live
 * OUTSIDE G1 identity), contentId/order binding with full expected-set
 * pre-validation before any source opens, contract-version gate on the
 * request path, and source close + best-effort wipe of the owned
 * transient buffer in `finally` (`use {}` — no filesystem writes exist
 * in this slice).
 *
 * Explicitly NOT in G4B: CreateViewModel bridge, filesystem-store
 * redesign, renderer algorithms, publishing, receive UI, M4. Music and
 * artwork stay reference-only (no audio/image-content handling here).
 * Unknown canonical contract versions fail closed via
 * [requireSupportedContractVersion]; unknown positive renderer
 * dependency versions are preserved for renderer gating by never
 * interpreting them (this slice neither reads nor alters them).
 * Cancellation ([CancellationException]) always propagates; sources
 * are closed by `use {}` on every path.
 */
object GeneratorSourceBinding {

    /**
     * Fails closed on any unknown canonical contract version, bound to
     * the G1 contract truth (not a local copy).
     */
    fun requireSupportedContractVersion(version: Int) {
        require(version == GeneratorExpression.CONTRACT_VERSION) {
            "unsupported generator contract: $version"
        }
    }

    private val CONTENT_HASH = Regex("[0-9a-f]{64}")

    /** G1-declared expectation for one authored slot (upright dims). */
    data class ExpectedOriginal(
        val contentId: String,
        val ordinal: Int,
        val contentHash: String,
        val uprightWidthPx: Int,
        val uprightHeightPx: Int,
    )

    /** EXIF-upright decode result (orientation already applied). */
    data class UprightPhoto(val widthPx: Int, val heightPx: Int)

    /** EXIF-applying decoder port (fake in tests; platform wiring later). */
    fun interface PhotoDecoderPort {
        suspend fun decodeUpright(jpeg: ByteArray): UprightPhoto
    }

    /**
     * One bound original: upright (identity-side) and normalized
     * (derived-side) facts kept explicitly distinct. Normalized bytes are
     * copied on retain and use content equality. [derived] carries the
     * derived artifact identity and NEVER enters G1 canonical bytes/hash
     * (see [SourceBinder.toPhotoRef], which only reads identity-side
     * fields).
     */
    data class BoundOriginal(
        val contentId: String,
        val ordinal: Int,
        val originalHash: String,
        val uprightWidthPx: Int,
        val uprightHeightPx: Int,
        val normalizedBytes: ByteArray,
        val normalizedWidthPx: Int,
        val normalizedHeightPx: Int,
        val derived: DerivedIdentity,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BoundOriginal) return false
            return contentId == other.contentId &&
                ordinal == other.ordinal &&
                originalHash == other.originalHash &&
                uprightWidthPx == other.uprightWidthPx &&
                uprightHeightPx == other.uprightHeightPx &&
                normalizedBytes.contentEquals(other.normalizedBytes) &&
                normalizedWidthPx == other.normalizedWidthPx &&
                normalizedHeightPx == other.normalizedHeightPx &&
                derived == other.derived
        }

        override fun hashCode(): Int {
            var result = contentId.hashCode()
            result = 31 * result + ordinal
            result = 31 * result + originalHash.hashCode()
            result = 31 * result + uprightWidthPx
            result = 31 * result + uprightHeightPx
            result = 31 * result + normalizedBytes.contentHashCode()
            result = 31 * result + normalizedWidthPx
            result = 31 * result + normalizedHeightPx
            result = 31 * result + derived.hashCode()
            return result
        }
    }

    /**
     * Derived artifact identity: SHA-256 over the normalized bytes plus
     * strictly-positive normalizer/codec policy versions. Lives OUTSIDE
     * G1 canonical identity by construction (no reader maps it into
     * `GeneratorInput`); renderers gate on these versions downstream.
     */
    data class DerivedIdentity(
        val normalizedHash: String,
        val normalizerVersion: Int,
        val codecVersion: Int,
    )

    /** Outcome of binding one source (no exceptions except cancellation). */
    sealed interface BindResult {
        data class Bound(val bound: BoundOriginal) : BindResult
        data class Rejected(val reason: String) : BindResult
    }

    /**
     * Sequential binder: callers feed sources one authored slot at a time
     * (see [bindAll]); at most one source is ever open. Holds no state,
     * persists nothing, writes no files.
     */
    class SourceBinder(
        private val normalizer: PhotoNormalizerPort,
        private val decoder: PhotoDecoderPort,
        private val normalizerVersion: Int = 1,
        private val codecVersion: Int = 1,
    ) {
        init {
            require(normalizerVersion > 0 && codecVersion > 0) {
                "normalizer/codec policy versions must be strictly positive"
            }
        }

        suspend fun bindOne(
            source: PhotoSource,
            expected: ExpectedOriginal,
            contractVersion: Int = GeneratorExpression.CONTRACT_VERSION,
        ): BindResult {
            currentCoroutineContext().ensureActive()
            requireSupportedContractVersion(contractVersion)
            if (expected.contentId.isBlank() ||
                expected.contentId.length > GeneratorDiscovery.MAX_ID_CHARS
            ) {
                return BindResult.Rejected("bad content id")
            }
            if (expected.ordinal < 0) return BindResult.Rejected("bad ordinal")
            if (!expected.contentHash.matches(CONTENT_HASH)) {
                return BindResult.Rejected("bad content hash")
            }
            if (expected.uprightWidthPx <= 0 || expected.uprightHeightPx <= 0) {
                return BindResult.Rejected("bad upright dims")
            }
            val original: ByteArray = try {
                source.openInputStream().use { stream ->
                    stream.readBoundedBytes(PhotoStagingPipeline.MAX_SOURCE_BYTES)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                return BindResult.Rejected("source over 32MiB bound")
            } catch (e: IOException) {
                return BindResult.Rejected("source unreadable")
            } catch (e: Exception) {
                return BindResult.Rejected("source failed")
            }
            try {
                currentCoroutineContext().ensureActive()
                if (sha256Hex(original) != expected.contentHash) {
                    return BindResult.Rejected("hash mismatch")
                }
                val upright = try {
                    decoder.decodeUpright(original)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return BindResult.Rejected("decode failed")
                }
                if (upright.widthPx != expected.uprightWidthPx || upright.heightPx != expected.uprightHeightPx) {
                    return BindResult.Rejected("dimension mismatch")
                }
                currentCoroutineContext().ensureActive()
                val normalized = try {
                    normalizer.normalize(original)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return BindResult.Rejected("normalization failed")
                }
                if (normalized.jpegBytes.size > PhotoArtifactEncryptor.MAX_PLAINTEXT_BYTES) {
                    return BindResult.Rejected("derived over budget")
                }
                val derivedBytes = normalized.jpegBytes.copyOf()
                return BindResult.Bound(
                    BoundOriginal(
                        contentId = expected.contentId,
                        ordinal = expected.ordinal,
                        originalHash = expected.contentHash,
                        uprightWidthPx = upright.widthPx,
                        uprightHeightPx = upright.heightPx,
                        normalizedBytes = derivedBytes,
                        normalizedWidthPx = normalized.width,
                        normalizedHeightPx = normalized.height,
                        derived = DerivedIdentity(
                            normalizedHash = sha256Hex(derivedBytes),
                            normalizerVersion = normalizerVersion,
                            codecVersion = codecVersion,
                        ),
                    ),
                )
            } finally {
                // Best-effort wipe of OUR transient copy (read fresh from
                // the source above); runs on success, rejection,
                // cancellation and Error alike without altering behavior.
                original.fill(0)
            }
        }

        /**
         * Binds pairs in order, one source open at a time; never stops
         * early. The complete expected set is validated BEFORE any source
         * is opened: exactly 3–5 slots, ordinal == index, unique bounded
         * opaque content ids (punctuation allowed — opacity, not charset),
         * lowercase 64-char SHA-256 hashes, positive upright dims. An
         * invalid set rejects every slot without touching any source.
         */
        suspend fun bindAll(
            pairs: List<Pair<PhotoSource, ExpectedOriginal>>,
            contractVersion: Int = GeneratorExpression.CONTRACT_VERSION,
        ): List<BindResult> {
            currentCoroutineContext().ensureActive()
            requireSupportedContractVersion(contractVersion)
            val setProblem = validateExpectedSet(pairs.map { it.second })
            if (setProblem != null) {
                return pairs.map { BindResult.Rejected(setProblem) }
            }
            return pairs.map { (source, expected) -> bindOne(source, expected, contractVersion) }
        }

        private fun validateExpectedSet(expected: List<ExpectedOriginal>): String? {
            if (expected.size !in ProtocolV1Limits.PHOTO_COUNT_MIN..ProtocolV1Limits.PHOTO_COUNT_MAX) {
                return "expected set must be 3..5 slots"
            }
            val ids = mutableSetOf<String>()
            expected.forEachIndexed { index, slot ->
                if (slot.ordinal != index) return "expected ordinal must equal index"
                if (slot.contentId.isBlank() || slot.contentId.length > GeneratorDiscovery.MAX_ID_CHARS) {
                    return "bad content id"
                }
                if (!ids.add(slot.contentId)) return "duplicate content id"
                if (!slot.contentHash.matches(CONTENT_HASH)) return "bad content hash"
                if (slot.uprightWidthPx <= 0 || slot.uprightHeightPx <= 0) return "bad upright dims"
            }
            return null
        }

        /** G1 photo record from a bound original (upright dims + original hash). */
        fun toPhotoRef(bound: BoundOriginal): GeneratorExpression.PhotoRef =
            GeneratorExpression.PhotoRef(
                contentId = bound.contentId,
                ordinal = bound.ordinal,
                widthPx = bound.uprightWidthPx,
                heightPx = bound.uprightHeightPx,
                contentHash = bound.originalHash,
            )

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
