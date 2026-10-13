package dev.hryshyn.remanence.telemetry

import android.content.Context
import dev.hryshyn.remanence.core.model.GeneratorExpression
import dev.hryshyn.remanence.core.model.GeneratorEditorialRows
import dev.hryshyn.remanence.ui.collage.CollageAnalysis
import dev.hryshyn.remanence.ui.collage.StyleRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class TelemetryConsent(val design: Boolean = false, val crops: Boolean = false)

internal interface TelemetryConsentStorage {
    fun load(): TelemetryConsent
    fun save(value: TelemetryConsent): Boolean
}

/** Device-only choices; every sharedpref domain is excluded by both backup rule files. */
internal class DeviceTelemetryConsentStorage(context: Context, name: String = "remanence-telemetry") : TelemetryConsentStorage {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun load() = TelemetryConsent(prefs.getBoolean("design", false), prefs.getBoolean("crops", false))
    override fun save(value: TelemetryConsent) = prefs.edit().putBoolean("design", value.design).putBoolean("crops", value.crops).commit()
}

/** No media/identifier-bearing model may enter these wire-shaped values. */
internal data class TelemetryChoice(val grammar: String, val style: String) {
    companion object {
        fun normalized(grammar: String, style: String): TelemetryChoice? {
            val number = when (grammar) {
                // The installed legacy editorial-rows producer is explicitly
                // grammar 5, not a free-form wire grammar/name.
                "remanence-editorial-rows", GeneratorEditorialRows.GRAMMAR_ID -> StyleRegistry.GRAMMAR
                else -> grammar.removePrefix("g").toIntOrNull() ?: return null
            }
            val bundled = StyleRegistry.style(style) ?: return null
            if (bundled.grammar != number) return null
            return TelemetryChoice("g$number", bundled.id)
        }
    }
}

internal data class TelemetryConfig(val photoCount: Int, val textBucket: String, val music: Boolean, val cover: Boolean) {
    companion object {
        fun from(input: GeneratorExpression.GeneratorInput) = TelemetryConfig(input.photos.size,
            input.note?.let { it.codePointCount(0, it.length) }?.let { if (it == 0) "none" else if (it <= 280) "short" else "long" } ?: "none",
            input.music != null, input.music?.artworkId != null)
    }
}

internal data class TelemetryBox(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun valid(limit: Int) = x in 0..limit && y in 0..limit && w in 1..(limit - x) && h in 1..(limit - y)
    fun array() = listOf(x, y, w, h)
    companion object {
        fun from(crop: GeneratorExpression.CropWindow) = TelemetryBox(crop.x, crop.y, crop.width, crop.height)
    }
}

internal data class TelemetryCropInput(val slot: Int, val final: TelemetryBox,
    val aspect: String, val faces: List<TelemetryBox>, val focus: List<Int>)

/** Cached authoritative analysis only. No model execution, image reads or reconstructed center maps. */
internal object CompactTelemetryAnalysis {
    data class Compact(val aspect: String, val faces: List<TelemetryBox>, val focus: List<Int>)
    fun from(analysis: CollageAnalysis?, width: Int, height: Int): Compact? {
        val a = analysis ?: return null
        if (a.width != width || a.height != height || width <= 0 || height <= 0 || a.gridSize < 8 ||
            a.importance.any { !it.isFinite() || it < 0 }) return null
        val pools = (0 until 64).map { cell ->
            val x0 = cell % 8 * a.gridSize / 8
            val x1 = (cell % 8 + 1) * a.gridSize / 8
            val y0 = cell / 8 * a.gridSize / 8
            val y1 = (cell / 8 + 1) * a.gridSize / 8
            var sum = 0.0
            for (y in y0 until y1) for (x in x0 until x1) sum += a.importance[y * a.gridSize + x]
            sum / ((x1 - x0) * (y1 - y0))
        }
        if (pools.any { !it.isFinite() }) return null
        val peak = pools.max()
        val focus = pools.map { if (peak > 0) (it / peak * 15).roundToInt().coerceIn(0, 15) else 0 }
        val faces = a.faces.mapNotNull { face ->
            val b = face.box
            if (!listOf(b.x, b.y, b.width, b.height).all { it.isFinite() } || b.width <= 0 || b.height <= 0) return@mapNotNull null
            val left = floor(b.x.coerceIn(0.0, 1.0) * 16).toInt()
            val top = floor(b.y.coerceIn(0.0, 1.0) * 16).toInt()
            val right = ceil((b.x + b.width).coerceIn(0.0, 1.0) * 16).toInt()
            val bottom = ceil((b.y + b.height).coerceIn(0.0, 1.0) * 16).toInt()
            TelemetryBox(left, top, right - left, bottom - top).takeIf { it.valid(16) }
        }.take(16)
        val ratio = width.toDouble() / height
        val aspect = if (ratio < .9) "portrait" else if (ratio <= 1.1) "square" else if (ratio < 2) "landscape" else "panorama"
        return Compact(aspect, faces, focus)
    }
}

internal fun interface TelemetryPendingSend { fun cancel() }
internal fun interface TelemetryTransport { fun send(bytes: ByteArray): TelemetryPendingSend }

/** All collection, revocation and enqueue admission share this fence. No persistent queue. */
class TelemetryCoordinator internal constructor(private val storage: TelemetryConsentStorage, private val transport: TelemetryTransport) {
    internal val fence = Any()
    private val mutableConsent = MutableStateFlow(runCatching { storage.load() }.getOrDefault(TelemetryConsent()))
    val consent: StateFlow<TelemetryConsent> = mutableConsent.asStateFlow()
    private val mutableSaveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> = mutableSaveFailed.asStateFlow()
    private val sessions = mutableSetOf<TelemetrySession>()
    private var sending: TelemetryPendingSend? = null
    private var epoch = 0L
    internal data class Admission(val consent: TelemetryConsent, val epoch: Long)
    internal fun admission() = synchronized(fence) { Admission(consent.value, epoch) }
    internal fun admits(admission: Admission) = admission.epoch == epoch && admission.consent == consent.value
    fun setDesign(enabled: Boolean) = update { it.copy(design = enabled) }
    fun setCrops(enabled: Boolean) = update { it.copy(crops = enabled) }
    private fun update(change: (TelemetryConsent) -> TelemetryConsent) = synchronized(fence) {
        val prior = consent.value
        val requested = change(prior)
        if (requested == prior && !saveFailed.value) return@synchronized
        // Clear both components: no backfill on newly granted consent, no old
        // cohort or mixed-consent report may race a withdrawal.
        clearSessionDataLocked()
        val persisted = runCatching { storage.save(requested) }.getOrDefault(false)
        // Any failed save fails this process entirely OFF. The panel warns
        // that an unwritable disk cannot guarantee this across process death.
        mutableConsent.value = if (persisted) requested else TelemetryConsent()
        mutableSaveFailed.value = !persisted
    }
    internal fun newSession(): TelemetrySession = synchronized(fence) { TelemetrySession(this).also { sessions += it } }
    fun clearSessionData() = synchronized(fence) { clearSessionDataLocked() }
    private fun clearSessionDataLocked() {
        epoch++ // disable→enable and account/session boundaries reject previously captured observations
        runCatching { sending?.cancel() }
        sending = null
        sessions.forEach { it.resetLocked() }
    }
    internal fun close(session: TelemetrySession) = synchronized(fence) { session.resetLocked(); sessions -= session }
    internal fun sendLocked(payload: JsonObject) {
        val bytes = payload.toString().encodeToByteArray()
        if (bytes.size > 32768) { bytes.fill(0); return }
        runCatching { sending?.cancel() }
        sending = null
        try { sending = transport.send(bytes) } catch (_: Exception) { bytes.fill(0) }
    }
    override fun toString() = "TelemetryCoordinator(<local-consent>)"
}

/** One ephemeral chooser cohort; no source media or stable photo/account identifiers. */
internal class TelemetrySession(private val owner: TelemetryCoordinator) {
    private var generation: Long? = null
    private var catalog = emptyList<TelemetryChoice>()
    private var indexMap = emptyList<Int>()
    private var config: TelemetryConfig? = null
    private var initial = emptyList<List<TelemetryBox?>>()
    private val shown = mutableListOf<Int>()
    private var lastShown: Int? = null
    private var truncated = false
    private var lastSelection: JsonObject? = null
    internal fun resetLocked() {
        generation = null; catalog = emptyList(); indexMap = emptyList(); config = null; initial = emptyList()
        shown.clear(); lastShown = null; truncated = false; lastSelection = null
    }
    fun reset() = synchronized(owner.fence) { resetLocked() }
    fun close() = owner.close(this)
    fun shown(admission: TelemetryCoordinator.Admission, build: Long, index: Int, choices: List<TelemetryChoice>, configuration: TelemetryConfig,
        original: List<List<TelemetryBox?>>) = synchronized(owner.fence) {
        val consent = owner.consent.value
        if (!owner.admits(admission) || (!consent.design && !consent.crops) || index !in choices.indices || choices.size !in 1..128 ||
            choices.any { TelemetryChoice.normalized(it.grammar, it.style) != it } ||
            configuration.textBucket !in setOf("none", "short", "long")) return@synchronized
        if (generation != build) {
            resetLocked(); generation = build
            // Keep presentation positions: two different geometries may use
            // the same public grammar/style pair. Collapsing pairs would lose
            // actual A→B→A browsing while adding no privacy benefit.
            catalog = choices.toList()
            indexMap = choices.indices.toList()
            if (consent.design) config = configuration
            if (consent.crops) initial = original.take(choices.size).map { slots -> slots.take(12).map { it?.takeIf { box -> box.valid(10000) }?.copy() } }
        }
        val visible = indexMap.getOrNull(index) ?: return@synchronized
        if (lastShown != visible) {
            lastShown = visible
            lastSelection = null // a genuine A→B→A return is not an unchanged repeated selection
            if (consent.design) {
                if (shown.size < 256) shown += visible else truncated = true
            }
        }
    }
    fun selected(admission: TelemetryCoordinator.Admission, build: Long, index: Int, finalCrops: List<TelemetryCropInput>) = synchronized(owner.fence) {
        val consent = owner.consent.value
        if (!owner.admits(admission) || generation != build || lastShown == null || index !in indexMap.indices || (!consent.design && !consent.crops)) return@synchronized
        val selected = indexMap[index]
        if (selected != lastShown) return@synchronized // unseen preparation/select is not exposure
        val choice = catalog[selected]
        val design = if (consent.design) config?.takeIf { it.photoCount in 1..12 }?.let { cfg -> buildJsonObject {
            put("photo_count", cfg.photoCount); put("text_bucket", cfg.textBucket); put("music", cfg.music); put("cover", cfg.cover)
            put("available", buildJsonArray { catalog.forEach { add(buildJsonObject { put("grammar", it.grammar); put("style", it.style) }) } })
            put("shown", buildJsonArray { shown.forEach { add(it) } }); put("selected", selected); put("sequence_truncated", truncated)
        } } else null
        val crops = if (consent.crops) finalCrops.take(12).mapNotNull { crop ->
            val start = initial.getOrNull(index)?.getOrNull(crop.slot) ?: return@mapNotNull null
            if (crop.slot !in 0..11 || !crop.final.valid(10000) || crop.focus.size != 64 || crop.focus.any { it !in 0..15 } ||
                crop.aspect !in setOf("portrait", "square", "landscape", "panorama") || crop.faces.size > 16 || crop.faces.any { !it.valid(16) }) return@mapNotNull null
            buildJsonObject {
                put("grammar", choice.grammar); put("style", choice.style); put("slot", crop.slot); put("aspect_bucket", crop.aspect)
                put("faces", buildJsonArray { crop.faces.forEach { add(boxJson(it)) } })
                put("focus_grid", buildJsonArray { crop.focus.forEach { add(it) } })
                put("initial", boxJson(start)); put("final", boxJson(crop.final)); put("edited", start != crop.final)
            }
        }.takeIf { it.isNotEmpty() } else null
        if (design == null && crops == null) return@synchronized
        val payload = buildJsonObject {
            put("schema_version", 1); put("crop_policy", "balanced-v1"); put("analysis_version", "import-field-3.3")
            design?.let { put("design", it) }; crops?.let { put("crops", JsonArray(it)) }
        }
        if (payload == lastSelection) return@synchronized
        lastSelection = payload
        owner.sendLocked(payload)
    }
    override fun toString() = "TelemetrySession(<ephemeral>)"
}

private fun boxJson(box: TelemetryBox) = buildJsonArray { box.array().forEach { add(it) } }
