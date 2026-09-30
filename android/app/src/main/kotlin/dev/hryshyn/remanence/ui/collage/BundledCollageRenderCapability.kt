package dev.hryshyn.remanence.ui.collage

import dev.hryshyn.remanence.core.crypto.CollageRenderCapability

/**
 * App-layer [CollageRenderCapability] backed by the authoritative
 * [StyleRegistry] and [FontRegistry]. Slice 1 advertises no mask; it now
 * advertises artwork so a sealed `mode == "thumbnail"` descriptor is admitted
 * for direct client-only fetch. The fetch itself is verified independently by
 * the artwork fetcher; a failed fetch degrades to the text/no-art layout.
 *
 * This adapter is not wired into any production route yet: it exists so the
 * capability can be unit-tested before the recipient routes opt in.
 */
object BundledCollageRenderCapability : CollageRenderCapability {

    override val enabled: Boolean = true

    override fun styleDigest(styleId: String, styleVersion: String): ByteArray? {
        val style = StyleRegistry.style(styleId) ?: return null
        if (style.version != styleVersion) return null
        return StyleRegistry.digest(styleId)
    }

    override fun fontResource(family: String, weight: Int, italic: Boolean): Int? =
        FontRegistry.resourceFor(family, weight, italic)

    override val fontVersion: Int = FontRegistry.VERSION
    override val paletteVersion: Int = CollageRenderer.PALETTE_REGISTRY_VERSION
    override val maskSupported: Boolean = false
    override val artworkSupported: Boolean = true
}
