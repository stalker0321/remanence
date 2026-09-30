package dev.hryshyn.remanence.core.recognition

/**
 * Frozen postcard-matcher S3 contract (slice S3 of T017 reconnaissance).
 *
 * Versioned boundary for RootSIFT + 1024-word sparse TF-IDF top-8 retrieval
 * with MAGSAC verification and the frozen baseline floors. This file carries
 * the profile/version/threshold/fingerprint pins and the deterministic
 * build/load/decision rules only: no vocabulary bytes, no gallery, no index
 * payload and no native matching live here, so catalog delivery stays a
 * separately owned, explicitly Not-verified step.
 *
 * The existing v1 profile, codec, thresholds, stored rows and tests are
 * untouched; mixed v1/S3 data fails closed at every entry point below.
 */
object FrozenMatcherS3Contract {

    /** Lane-chosen profile id (the freeze pins thresholds/fingerprints, not the id string). */
    const val S3_PROFILE_ID: String = "postcard-frozen-matcher-s3"

    /** Profile format version, distinct from v1 (`RecognitionProfile.FORMAT_VERSION_1`). */
    const val S3_FORMAT_VERSION: Int = 2

    /** Frozen retrieval parameters: vocabulary size and shortlist depth. */
    const val VOCABULARY_WORDS: Int = 1024
    const val RETRIEVAL_TOP_K: Int = 8

    /** Frozen geometric verifier. */
    const val ESTIMATOR_ID: String = "MAGSAC"

    /** Baseline floors, exactly as frozen (no tuning without a new benchmark). */
    const val BASELINE_MIN_SCORE: Double = 12.0
    const val BASELINE_MIN_RUNNER_UP_MARGIN: Double = 1.6
    const val BASELINE_MIN_INLIERS: Int = 12
    const val BASELINE_MIN_COVERAGE: Double = 0.04
    const val BASELINE_MIN_INLIER_RATIO: Double = 0.30

    /**
     * Enrollment fingerprint pin: the Redmi 79-identity enrollment set the
     * frozen benchmark ran against. Stored/transmitted as lowercase hex.
     */
    const val ENROLLMENT_FINGERPRINT_HEX: String =
        "e8b264cf08f1925f24e5996af15268adbce8e8daa9b0aa7b6351bd1be865f45d"

    /**
     * Index fingerprint pin: the built retrieval index over that enrollment
     * set. Stored/transmitted as lowercase hex.
     */
    const val INDEX_FINGERPRINT_HEX: String =
        "7f332dfd4bf55c26fad00e23d46e111871aadeeedeb0872ce708168e43d28855"

    const val FINGERPRINT_BYTES: Int = 32
    const val MAX_INDEX_ENTRIES: Int = 10_000
    const val MAX_ENTRY_ID_BYTES: Int = 128
    const val MAX_ENTRY_BLOB_BYTES: Int = 1 shl 20

    /** One frozen baseline observation feeding the accept/reject decision. */
    data class BaselineObservation(
        val score: Double,
        val runnerUpMargin: Double,
        val inliers: Int,
        val coverage: Double,
        val inlierRatio: Double,
    )

    /**
     * Frozen baseline accept: every floor must hold simultaneously. NaN or
     * infinite inputs compare false and therefore fail closed.
     */
    fun passesBaseline(observation: BaselineObservation): Boolean {
        if (!observation.score.isFinite() || !observation.runnerUpMargin.isFinite() ||
            !observation.coverage.isFinite() || !observation.inlierRatio.isFinite()
        ) {
            return false
        }
        return observation.score >= BASELINE_MIN_SCORE &&
            observation.runnerUpMargin >= BASELINE_MIN_RUNNER_UP_MARGIN &&
            observation.inliers >= BASELINE_MIN_INLIERS &&
            observation.coverage >= BASELINE_MIN_COVERAGE &&
            observation.inlierRatio >= BASELINE_MIN_INLIER_RATIO
    }

    /** One versioned index entry. The profile tag travels with every row. */
    data class IndexEntry(
        val profileId: String,
        val entryId: String,
        val blob: ByteArray,
    )

    /** Validated index header returned by [loadIndex]. */
    data class IndexHeader(
        val formatVersion: Int,
        val profileId: String,
        val enrollmentFingerprintHex: String,
        val indexFingerprintHex: String,
        val entryCount: Int,
    )

    /**
     * Builds a deterministic versioned index blob. The header pins profile,
     * format version and both catalog fingerprints; every entry must already
     * carry the S3 profile id or the build fails closed.
     */
    fun buildIndex(
        entries: List<IndexEntry>,
        enrollmentFingerprintHex: String = ENROLLMENT_FINGERPRINT_HEX,
        indexFingerprintHex: String = INDEX_FINGERPRINT_HEX,
    ): ByteArray {
        require(entries.size <= MAX_INDEX_ENTRIES) { "index entry count out of bounds" }
        entries.forEach { require(it.profileId == S3_PROFILE_ID) { "mixed-profile index entry" } }
        val enrollment = decodeFingerprint(enrollmentFingerprintHex)
        val index = decodeFingerprint(indexFingerprintHex)
        val profileBytes = S3_PROFILE_ID.toByteArray(Charsets.UTF_8)
        var size = 4 + 4 + 4 + profileBytes.size + FINGERPRINT_BYTES + FINGERPRINT_BYTES + 4
        val entryBytes = entries.map { entry ->
            val id = entry.entryId.toByteArray(Charsets.UTF_8)
            require(id.isNotEmpty() && id.size <= MAX_ENTRY_ID_BYTES) { "index entry id out of bounds" }
            require(entry.blob.size <= MAX_ENTRY_BLOB_BYTES) { "index entry blob out of bounds" }
            size += 4 + id.size + 4 + entry.blob.size
            id to entry.blob
        }
        val buffer = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putInt(S3_FORMAT_VERSION)
        buffer.putInt(profileBytes.size)
        buffer.put(profileBytes)
        buffer.put(enrollment)
        buffer.put(index)
        buffer.putInt(entries.size)
        entryBytes.forEach { (id, blob) ->
            buffer.putInt(id.size)
            buffer.put(id)
            buffer.putInt(blob.size)
            buffer.put(blob)
        }
        return buffer.array()
    }

    /**
     * Loads and strictly validates a versioned index blob. Wrong magic,
     * format version, profile id, fingerprint, entry alignment or any trailing
     * bytes fail closed. Returns the validated header; entry payloads stay
     * caller-owned and are not retained.
     */
    fun loadIndex(bytes: ByteArray): IndexHeader {
        try {
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
            require(buffer.remaining() >= 4 + 4 + 4 + FINGERPRINT_BYTES + FINGERPRINT_BYTES + 4) {
                "index blob truncated"
            }
            require(buffer.int == MAGIC) { "index magic mismatch" }
            require(buffer.int == S3_FORMAT_VERSION) { "unsupported index format version" }
            val profileLen = buffer.int
            require(profileLen in 1..MAX_ENTRY_ID_BYTES && buffer.remaining() >= profileLen) {
                "index profile id out of bounds"
            }
            val profileBytes = ByteArray(profileLen)
            buffer.get(profileBytes)
            require(profileBytes.toString(Charsets.UTF_8) == S3_PROFILE_ID) {
                "mixed-profile index blob"
            }
            val enrollment = ByteArray(FINGERPRINT_BYTES)
            buffer.get(enrollment)
            require(enrollment.contentEquals(decodeFingerprint(ENROLLMENT_FINGERPRINT_HEX))) {
                "index enrollment fingerprint mismatch"
            }
            val index = ByteArray(FINGERPRINT_BYTES)
            buffer.get(index)
            require(index.contentEquals(decodeFingerprint(INDEX_FINGERPRINT_HEX))) {
                "index fingerprint mismatch"
            }
            val count = buffer.int
            require(count in 0..MAX_INDEX_ENTRIES) { "index entry count out of bounds" }
            repeat(count) {
                require(buffer.remaining() >= 4) { "index entry truncated" }
                val idLen = buffer.int
                require(idLen in 1..MAX_ENTRY_ID_BYTES && buffer.remaining() >= idLen) {
                    "index entry id out of bounds"
                }
                buffer.position(buffer.position() + idLen)
                require(buffer.remaining() >= 4) { "index entry truncated" }
                val blobLen = buffer.int
                require(blobLen in 0..MAX_ENTRY_BLOB_BYTES && buffer.remaining() >= blobLen) {
                    "index entry blob out of bounds"
                }
                buffer.position(buffer.position() + blobLen)
            }
            require(!buffer.hasRemaining()) { "index blob has trailing bytes" }
            return IndexHeader(
                formatVersion = S3_FORMAT_VERSION,
                profileId = S3_PROFILE_ID,
                enrollmentFingerprintHex = ENROLLMENT_FINGERPRINT_HEX,
                indexFingerprintHex = INDEX_FINGERPRINT_HEX,
                entryCount = count,
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("index blob is malformed")
        }
    }

    /**
     * Query-side guard: a candidate row participates in S3 matching only when
     * it carries the S3 profile id. v1 rows (or anything else) never reach the
     * S3 decision path.
     */
    fun requireS3Row(profileId: String) {
        require(profileId == S3_PROFILE_ID) { "mixed-profile query row" }
    }

    internal fun decodeFingerprint(hex: String): ByteArray {
        require(hex.length == FINGERPRINT_BYTES * 2) { "fingerprint hex length out of bounds" }
        return ByteArray(FINGERPRINT_BYTES) { index ->
            val pair = hex.substring(index * 2, index * 2 + 2)
            require(pair.all { it in '0'..'9' || it in 'a'..'f' }) { "fingerprint hex must be lowercase hex" }
            pair.toInt(16).toByte()
        }
    }

    private const val MAGIC: Int = 0x504D4933
}
