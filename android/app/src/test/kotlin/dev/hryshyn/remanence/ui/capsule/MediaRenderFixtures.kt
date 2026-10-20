package dev.hryshyn.remanence.ui.capsule

import android.graphics.Bitmap
import dev.hryshyn.remanence.core.model.GeneratorExpression as G
import dev.hryshyn.remanence.ui.collage.CollageRenderer
import dev.hryshyn.remanence.ui.collage.FontRegistry
import dev.hryshyn.remanence.ui.collage.StyleRegistry
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

internal val mediaPhotoColors = listOf(0xffd05b49.toInt(), 0xff4e8c75.toInt(), 0xff526fbd.toInt())
internal const val MEDIA_COVER_COLOR = 0xffdf32a7.toInt()
internal fun mediaPng(width: Int, height: Int, color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(color)
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
    }
}

/** Synthetic supported layouts; renderer/prepare/fonts/export are real. */
internal fun mediaExpression(version: Int, hasTrack: Boolean, cover: ByteArray? = null): G.ResolvedExpression {
    val input = G.GeneratorInput("synthetic-owner", 1,
        listOf(160 to 80, 80 to 160, 120 to 120).mapIndexed { i, (w, h) ->
            G.PhotoRef("p$i", i, w, h, ('a' + i).toString().repeat(64))
        }, null, null)
    val art = cover?.let { G.ArtworkRef("catalog-sample", "https://art.example.org/synthetic.png",
        MessageDigest.getInstance("SHA-256").digest(it), "image/png", 80, 80) }
    val style = requireNotNull(StyleRegistry.style("press"))
    return G.ResolvedExpression(
        canvasVersion = 1, grammarId = if (version == 4) "24" else "g-hold", grammarVersion = 3,
        branchId = "synthetic-media", input = input,
        placements = input.photos.mapIndexed { i, p -> G.Placement(p.contentId, 16 + i * 110, 20, 108, 200,
            G.CropWindow(0, 0, 10000, 10000), null, null) },
        noteTreatment = "regular-region", diagnostics = emptyList(), fontVersion = FontRegistry.VERSION,
        paletteVersion = CollageRenderer.PALETTE_REGISTRY_VERSION, expressionContractVersion = version,
        style = G.StyleBinding(style.id, style.version, requireNotNull(StyleRegistry.digest(style.id))),
        noteType = G.TextType("StudySans", 16, 400, false, 18),
        musicRegion = if (!hasTrack) null else G.MusicRegion(16, 280, 328, 100,
            if (cover == null) "text" else if (version == 4) "pulse" else "thumbnail",
            G.TextType("StudySans", 14, 400, false, 16), art),
    )
}
