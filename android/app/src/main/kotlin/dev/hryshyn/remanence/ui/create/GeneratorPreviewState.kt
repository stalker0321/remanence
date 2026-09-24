package dev.hryshyn.remanence.ui.create

import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.CapsulePhotoIdentity
import dev.hryshyn.remanence.create.GeneratorPreviewLoader
import dev.hryshyn.remanence.create.LoadedPreviewSource

/**
 * One in-memory preview bitmap source (upright normalized JPEG + dims) keyed
 * by the authored content id used in the G1 expression.
 */
data class GeneratorPreviewSource(
    val contentId: String,
    val jpegBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is GeneratorPreviewSource &&
            contentId == other.contentId &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            jpegBytes.contentEquals(other.jpegBytes)

    override fun hashCode(): Int {
        var result = contentId.hashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        result = 31 * result + jpegBytes.contentHashCode()
        return result
    }
}

/**
 * Typed preview admission state. Never blank and never a partial postcard:
 * every non-[Ready] case carries copy the UI must render.
 */
sealed interface GeneratorPreviewState {
    data object Idle : GeneratorPreviewState
    data object Loading : GeneratorPreviewState

    /** Photos resolve (note absent/empty); [expression] is ready to draw. */
    data class Ready(
        val expression: GeneratorExpression.ResolvedExpression,
        val sources: List<GeneratorPreviewSource>,
    ) : GeneratorPreviewState

    /** A non-empty note needs on-device text measurement before display. */
    data class NotePending(
        val input: GeneratorExpression.GeneratorInput,
        val sources: List<GeneratorPreviewSource>,
        val noteUtf8Bytes: Int,
    ) : GeneratorPreviewState

    /** Applicable but unresolvable (e.g. an extreme aspect ratio). */
    data class Incompatible(val reason: String) : GeneratorPreviewState

    /** Structurally invalid selection/note; fail closed. */
    data class Rejected(val reasons: List<String>) : GeneratorPreviewState

    /** No safe source ownership (missing loader, unreadable source, no owner). */
    data class Unavailable(val reason: String) : GeneratorPreviewState
}

/**
 * Pure preview orchestration (host-testable, no Android/Compose). Loads the
 * selected picker ids into bounded upright sources, builds the G1 input, and
 * runs the BER1 planner without any measurement port. A non-empty note yields
 * [GeneratorPreviewState.NotePending] — never a fabricated fit.
 *
 * Caching is keyed by picker-id list so a note-only edit reuses the already
 * loaded sources; [reset] drops them on any ownership change.
 */
class GeneratorPreviewCoordinator(private val loader: GeneratorPreviewLoader?) {

    private var cachedIds: List<String> = emptyList()
    private var cachedLoaded: List<LoadedPreviewSource> = emptyList()

    fun reset() {
        cachedIds = emptyList()
        cachedLoaded = emptyList()
    }

    /**
     * Zeroizes retained plaintext preview JPEG bytes and drops the cache.
     * Call only after the preview state has been cleared to [Idle], so no
     * in-flight composition still reads these arrays.
     */
    fun zeroize() {
        cachedLoaded.forEach { it.previewJpegBytes.fill(0) }
        cachedIds = emptyList()
        cachedLoaded = emptyList()
    }

    suspend fun compute(
        ownerId: String?,
        epoch: Long,
        pickerIds: List<String>,
        note: String?,
    ): GeneratorPreviewState {
        val owner = ownerId?.takeIf { it.isNotBlank() }
            ?: return GeneratorPreviewState.Unavailable("owner is unavailable")
        if (pickerIds.size !in MIN_PHOTOS..MAX_PHOTOS) {
            return GeneratorPreviewState.Rejected(listOf("select 3..5 photos"))
        }
        if (pickerIds.distinct().size != pickerIds.size) {
            return GeneratorPreviewState.Rejected(listOf("photos must be unique"))
        }
        val effectiveLoader = loader
            ?: return GeneratorPreviewState.Unavailable("preview loader is not configured")

        val loaded: List<LoadedPreviewSource> =
            if (pickerIds == cachedIds && cachedLoaded.size == pickerIds.size) {
                cachedLoaded
            } else {
                val fresh = ArrayList<LoadedPreviewSource>(pickerIds.size)
                for (pickerId in pickerIds) {
                    val one = effectiveLoader.load(pickerId)
                        ?: return GeneratorPreviewState.Unavailable("could not read a selected photo")
                    fresh += one
                }
                cachedIds = pickerIds
                cachedLoaded = fresh
                fresh
            }

        if (note != null && note.toByteArray(Charsets.UTF_8).size > MAX_NOTE_BYTES) {
            return GeneratorPreviewState.Rejected(listOf("the note exceeds its byte limit"))
        }

        val sources = loaded.mapIndexed { index, source ->
            GeneratorPreviewSource(
                contentId = CapsulePhotoIdentity.contentIdFor(source.originalHash),
                jpegBytes = source.previewJpegBytes,
                widthPx = source.previewWidthPx,
                heightPx = source.previewHeightPx,
            )
        }
        val photos = loaded.mapIndexed { index, source ->
            GeneratorExpression.PhotoRef(
                contentId = CapsulePhotoIdentity.contentIdFor(source.originalHash),
                ordinal = index,
                widthPx = source.uprightWidthPx,
                heightPx = source.uprightHeightPx,
                contentHash = source.originalHash,
            )
        }
        val input = GeneratorExpression.GeneratorInput(
            ownerId = owner,
            epoch = epoch,
            photos = photos,
            note = note?.takeIf { it.isNotEmpty() },
            music = null,
        )
        return when (val plan = GeneratorEditorialRows.plan(input)) {
            is GeneratorEditorialRows.PlanResult.Planned ->
                GeneratorPreviewState.Ready(plan.expression, sources)
            is GeneratorEditorialRows.PlanResult.NeedsNoteMeasure ->
                GeneratorPreviewState.NotePending(input, sources, plan.noteUtf8Bytes)
            is GeneratorEditorialRows.PlanResult.Incompatible ->
                GeneratorPreviewState.Incompatible(plan.reason)
            is GeneratorEditorialRows.PlanResult.Invalid ->
                GeneratorPreviewState.Rejected(plan.reasons)
        }
    }

    private companion object {
        const val MIN_PHOTOS = 3
        const val MAX_PHOTOS = 5
        const val MAX_NOTE_BYTES = 1000
    }
}
