package dev.hryshyn.remanence.ui.collage

/**
 * The song band, 9 November 2026 rules; the site's `songBand.ts` line for
 * line. The band fits the words, not the other way round:
 *
 * - `plate`: a filled band (64 px in every layout). The cover is a square the
 *   full band height on the left; without one, the play button sits in that
 *   square. The title is one line in the style's own music type, cut with an
 *   ellipsis; the artist is a small capitals line under it.
 * - `caption`: no fill, a rule on top, the cover or a play circle, then title
 *   and artist on ONE line. Offered only when the whole line fits; a
 *   recipient whose metrics disagree draws the plate instead.
 * - `card`: the layout 6 spine (56 px). The title runs bottom to top along the
 *   whole spine, larger than the band type, then the artist, then the play
 *   button at the foot.
 *
 * One layout feeds prepare, the canvas and the sender's caption choice.
 */
object SongBand {

    data class Face(val family: String, val size: Int, val weight: Int, val italic: Boolean, val line: Int)

    /** Horizontal runs: the line box's top-left. Vertical runs: the line box's left edge and the run's bottom end. */
    data class Run(val text: String, val face: Face, val x: Int, val y: Int, val vertical: Boolean)

    enum class PlayStyle { CIRCLE, RING, GLYPH }

    data class Layout(
        val mode: String,
        val cover: CollageRenderer.RectI?,
        val play: CollageRenderer.RectI,
        val playStyle: PlayStyle,
        val divider: CollageRenderer.RectI?,
        val filled: Boolean,
        val runs: List<Run>,
        /** A voice note's waveform, one bar per peak; empty for a song. */
        val bars: List<CollageRenderer.RectI> = emptyList(),
        /** The microphone beside a voice note's length (plate, card). */
        val mic: CollageRenderer.RectI? = null,
    )

    data class Words(val title: String, val artistDisplay: String)

    /** A received voice note's bars (0..255, [VOICE_PEAKS] of them) and length. */
    class VoiceWords(val peaks: IntArray, val durationMs: Int)

    fun interface RunWidth {
        fun width(text: String, face: Face): Int
    }

    /** The artist line: small capitals in the bundled sans. */
    val ARTIST = Face("StudySans", 12, 400, false, 14)
    private const val ELLIPSIS = "…"

    fun isSongBandMode(mode: String): Boolean = mode == "plate" || mode == "caption" || mode == "card"

    /** The longest prefix (ellipsis added when cut) that fits [width]; null when not even one letter fits. */
    fun fitRun(text: String, face: Face, width: Int, measure: RunWidth): String? {
        val whole = text.trim()
        if (whole.isEmpty()) return null
        if (measure.width(whole, face) <= width) return whole
        val chars = whole.codePoints().toArray()
        var low = 1
        var high = chars.size - 1
        var best: String? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = String(chars, 0, mid).trimEnd() + ELLIPSIS
            if (measure.width(candidate, face) <= width) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    fun layout(region: CollageRenderer.RectI, mode: String, type: Face, words: Words, measure: RunWidth, cover: Boolean): Layout? =
        when (mode) {
            "card" -> card(region, type, words, measure)
            "caption" -> caption(region, type, words, measure, cover)
            else -> plate(region, type, words, measure, cover)
        }

    private fun plate(region: CollageRenderer.RectI, type: Face, words: Words, measure: RunWidth, cover: Boolean): Layout? {
        val side = region.height
        val left = region.x + side + 12
        val glyph = CollageRenderer.RectI(region.x + region.width - 14 - 16, region.y + (side - 18) / 2, 16, 18)
        val right = if (cover) glyph.x - 12 else region.x + region.width - 14
        val block = type.line + 3 + ARTIST.line
        if (right - left < 40 || block > side - 8) return null
        val title = fitRun(words.title, type, right - left, measure) ?: return null
        val artist = fitRun(words.artistDisplay.uppercase(), ARTIST, right - left, measure)
        val top = region.y + (side - (if (artist == null) type.line else block)) / 2
        val runs = buildList {
            add(Run(title, type, left, top, vertical = false))
            if (artist != null) add(Run(artist, ARTIST, left, top + type.line + 3, vertical = false))
        }
        return if (cover) {
            Layout("plate", CollageRenderer.RectI(region.x, region.y, side, side), glyph, PlayStyle.GLYPH, null, true, runs)
        } else {
            Layout(
                mode = "plate",
                cover = null,
                play = CollageRenderer.RectI(region.x + (side - 20) / 2, region.y + (side - 22) / 2, 20, 22),
                playStyle = PlayStyle.GLYPH,
                divider = CollageRenderer.RectI(region.x + side - 1, region.y, 1, side),
                filled = true,
                runs = runs,
            )
        }
    }

    private fun caption(region: CollageRenderer.RectI, type: Face, words: Words, measure: RunWidth, cover: Boolean): Layout? {
        val bodyY = region.y + 8
        val bodyH = region.height - 8
        val lead = if (cover) 44 else 36
        val leadBox = CollageRenderer.RectI(region.x, bodyY + (bodyH - lead) / 2, lead, lead)
        val ring = CollageRenderer.RectI(region.x + region.width - 28, bodyY + (bodyH - 28) / 2, 28, 28)
        val left = leadBox.x + lead + 12
        val right = if (cover) ring.x - 12 else region.x + region.width
        val artistSize = maxOf(12, Math.round(type.size * 0.7f))
        val artistFace = Face(type.family, artistSize, 400, type.italic, Math.ceil(artistSize * 1.12).toInt())
        val title = words.title.trim()
        val artist = words.artistDisplay.trim()
        if (title.isEmpty() || type.line > bodyH) return null
        val titleWidth = measure.width(title, type)
        val artistWidth = if (artist.isEmpty()) 0 else measure.width(artist, artistFace)
        // The whole line or nothing: a caption never cuts the words.
        if (left + titleWidth + (if (artist.isEmpty()) 0 else 8 + artistWidth) > right) return null
        val top = bodyY + (bodyH - type.line) / 2
        val runs = buildList {
            add(Run(title, type, left, top, vertical = false))
            // Baselines align: the artist's line box sits so both baselines meet.
            if (artist.isNotEmpty()) add(Run(artist, artistFace, left + titleWidth + 8, top + type.size - artistFace.size, vertical = false))
        }
        return if (cover) {
            Layout("caption", leadBox, ring, PlayStyle.RING, null, false, runs)
        } else {
            Layout("caption", null, leadBox, PlayStyle.CIRCLE, null, false, runs)
        }
    }

    private fun card(region: CollageRenderer.RectI, type: Face, words: Words, measure: RunWidth): Layout? {
        val titleSize = minOf(30, Math.round(type.size * 1.3f))
        val titleFace = type.copy(size = titleSize, line = Math.ceil(titleSize * 1.12).toInt())
        val button = 34
        if (titleFace.line > region.width - 8 || region.height < 200) return null
        val play = CollageRenderer.RectI(region.x + (region.width - button) / 2, region.y + region.height - 14 - button, button, button)
        val foot = play.y - 16
        val artist = fitRun(words.artistDisplay.uppercase(), ARTIST, minOf(200, (foot - region.y - 24) / 3), measure)
        val artistLength = if (artist == null) 0 else measure.width(artist, ARTIST)
        val titleFoot = foot - (if (artist == null) 0 else artistLength + 16)
        val title = fitRun(words.title, titleFace, titleFoot - (region.y + 24), measure) ?: return null
        val titleLength = measure.width(title, titleFace)
        val runs = buildList {
            // Centred in the length above the artist.
            add(Run(title, titleFace, region.x + (region.width - titleFace.line) / 2, titleFoot - (titleFoot - (region.y + 24) - titleLength) / 2, vertical = true))
            if (artist != null) add(Run(artist, ARTIST, region.x + (region.width - ARTIST.line) / 2, foot, vertical = true))
        }
        return Layout("card", null, play, PlayStyle.RING, null, true, runs)
    }

    /*
     * A voice note in the song band (ADR-021 §5, the owner's look of 23
     * November): the band's own three forms with the waveform where the title
     * was and the length (with a microphone on the plate and the spine) where
     * the artist was. No words, so it never needs to fit and never refuses.
     * The site's `voiceBandLayout` line for line.
     */
    const val VOICE_PEAKS = 48
    private const val MIC = 12

    /** The song band form a `voice-` mode borrows. */
    fun voiceBandShape(mode: String): String = when (mode) {
        "voice-card" -> "card"
        "voice-caption" -> "caption"
        else -> "plate"
    }

    /** "0:42": whole seconds, at least one. */
    fun voiceLength(durationMs: Int): String {
        val seconds = maxOf(1, Math.round(durationMs / 1000.0).toInt())
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    /** Bars across [span] (horizontal: along x, vertical: along y), each centred on the band's axis. */
    private fun waveBars(peaks: IntArray, along: Int, span: Int, across: Double, depth: Int, vertical: Boolean): List<CollageRenderer.RectI> {
        val count = peaks.size
        if (count == 0 || span < count * 2) return emptyList()
        val step = span.toDouble() / count
        val thick = maxOf(2, minOf(4, Math.floor(step * 0.6).toInt()))
        val least = 3
        return peaks.mapIndexed { index, peak ->
            val size = maxOf(least, Math.round(least + (depth - least) * peak / 255.0).toInt())
            val start = Math.round(along + index * step + (step - thick) / 2).toInt()
            val side = Math.round(across - size / 2.0).toInt()
            if (vertical) CollageRenderer.RectI(side, start, size, thick) else CollageRenderer.RectI(start, side, thick, size)
        }
    }

    fun voiceLayout(region: CollageRenderer.RectI, mode: String, type: Face, peaks: IntArray, durationMs: Int, measure: RunWidth): Layout {
        val length = voiceLength(durationMs)
        if (mode == "card") {
            val button = 34
            val play = CollageRenderer.RectI(region.x + (region.width - button) / 2, region.y + region.height - 14 - button, button, button)
            val mic = CollageRenderer.RectI(region.x + (region.width - MIC) / 2, play.y - 12 - MIC, MIC, MIC)
            val foot = mic.y - 6
            val run = Run(length, ARTIST, region.x + (region.width - ARTIST.line) / 2, foot, vertical = true)
            val top = region.y + 24
            val bottom = foot - measure.width(length, ARTIST) - 18
            val bars = waveBars(peaks, top, bottom - top, region.x + region.width / 2.0, minOf(34, region.width - 16), vertical = true)
            return Layout("card", null, play, PlayStyle.RING, null, true, listOf(run), bars, mic)
        }
        if (mode == "caption") {
            val bodyY = region.y + 8
            val bodyHeight = region.height - 8
            val lead = 36
            val play = CollageRenderer.RectI(region.x, bodyY + (bodyHeight - lead) / 2, lead, lead)
            val lengthWidth = measure.width(length, type)
            val top = bodyY + (bodyHeight - type.line) / 2
            val run = Run(length, type, region.x + region.width - lengthWidth, top, vertical = false)
            val left = play.x + lead + 12
            val right = run.x - 12
            val bars = waveBars(peaks, left, right - left, bodyY + bodyHeight / 2.0, minOf(22, bodyHeight - 8), vertical = false)
            return Layout("caption", null, play, PlayStyle.CIRCLE, null, false, listOf(run), bars, null)
        }
        val side = region.height
        val left = region.x + side + 12
        val right = region.x + region.width - 14
        val waveDepth = minOf(28, side - ARTIST.line - 20)
        val block = waveDepth + 6 + ARTIST.line
        val top = region.y + (side - block) / 2
        val lineTop = top + waveDepth + 6
        val mic = CollageRenderer.RectI(left, lineTop + (ARTIST.line - MIC) / 2, MIC, MIC)
        val run = Run(length, ARTIST, left + MIC + 5, lineTop, vertical = false)
        val bars = waveBars(peaks, left, right - left, top + waveDepth / 2.0, waveDepth, vertical = false)
        return Layout(
            "plate",
            null,
            CollageRenderer.RectI(region.x + (side - 20) / 2, region.y + (side - 22) / 2, 20, 22),
            PlayStyle.GLYPH,
            CollageRenderer.RectI(region.x + side - 1, region.y, 1, side),
            true,
            listOf(run),
            bars,
            mic,
        )
    }
}
