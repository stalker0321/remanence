package dev.hryshyn.remanence.core.crypto

import dev.hryshyn.remanence.core.model.GeneratorExpression
import java.security.MessageDigest

/**
 * Narrow receiver render capability for the V5 finished collage (contract V3).
 *
 * The authoritative style/font registries live in the app/UI layer, so
 * [ExpressionReceiverAdmission] accepts this port instead of depending on
 * `:app`. [Disabled] preserves the current blanket refusal at every existing
 * call site (the default).
 */
interface CollageRenderCapability {

    /** False keeps the blanket V3 refusal; true enables the capability gates. */
    val enabled: Boolean

    /** Canonical STYD01 v1 digest for (styleId, styleVersion), or null when not bundled. */
    fun styleDigest(styleId: String, styleVersion: String): ByteArray?

    /** Bundled font resource id for a face, or null when the face is not shipped. */
    fun fontResource(family: String, weight: Int, italic: Boolean): Int?

    /** Bundled font registry contract version. */
    val fontVersion: Int

    /** Bundled palette registry contract version. */
    val paletteVersion: Int

    /** Slice-1 ships no mask renderer. */
    val maskSupported: Boolean

    /** Slice-1 ships no cover-art fetcher/renderer. */
    val artworkSupported: Boolean

    companion object {
        /** Blanket fail-closed capability; the default for every existing caller. */
        val Disabled: CollageRenderCapability = object : CollageRenderCapability {
            override val enabled: Boolean = false
            override fun styleDigest(styleId: String, styleVersion: String): ByteArray? = null
            override fun fontResource(family: String, weight: Int, italic: Boolean): Int? = null
            override val fontVersion: Int = -1
            override val paletteVersion: Int = -1
            override val maskSupported: Boolean = false
            override val artworkSupported: Boolean = false
        }
    }
}

/**
 * ADR-018 step 3 (receiver): decides whether a decrypted content manifest may
 * be rendered as the exact BER1 layout. v1 has no artifact (caller keeps the
 * existing v1 photo path); v2 is admitted only when the sealed expression is
 * structurally valid and fully consistent with the manifest note and photo
 * ordinals. Any other case returns a typed [Result.Unsupported] so the
 * presentation can render NOTHING instead of a wrong/fallback layout.
 *
 * Structural rules A1-A9 live in [GeneratorExpression.validateResolved] and
 * run first, unchanged. The note <-> noteRegion equivalence rule is a legacy
 * v2 (BER1) rule scoped to [GeneratorExpression.EXPRESSION_CONTRACT_V2]; v1 has
 * no noteRegion slot.
 *
 * V3 additionally requires a [CollageRenderCapability]: exact style
 * id/version/digest (constant-time), font/palette registry versions, every
 * sealed note/music face bundled, no mask, and music that is either text-only
 * (`mode == "text"`, no artwork) or a thumbnail descriptor
 * (`mode == "thumbnail"`, artwork present) when the capability advertises
 * artwork support. The descriptor's URL/host/digest are re-verified at fetch
 * time, never here, so admission itself performs no network work.
 * With the default [CollageRenderCapability.Disabled] the V3 result is the
 * existing blanket `"finished collage rendering is not supported"`.
 *
 * V4 (full frozen V5) keeps every V3 gate and additionally requires every
 * sealed provenance face bundled, a music mode in the frozen nine with the
 * frozen artwork compatibility (text-only bands carry no artwork; cover
 * bands with artwork require artwork support; bare `inline`/`pulse` bands
 * without artwork ride the text path), and no mask/contentRect overrides.
 * Decor planes and photo aperture shapes are pure canvas geometry and need
 * no registry beyond the enabled capability.
 */
object ExpressionReceiverAdmission {

    sealed interface Result {
        data class Supported(
            val expression: GeneratorExpression.ResolvedExpression,
        ) : Result

        data class Unsupported(val reason: String) : Result
    }

    /** Legacy call sites: no V3 capability, blanket fail-closed. */
    fun admit(content: ContentManifestContent): Result = admit(content, CollageRenderCapability.Disabled)

    fun admit(content: ContentManifestContent, capability: CollageRenderCapability): Result {
        if (content.protocolVersion != 2) {
            return Result.Unsupported("content manifest carries no v2 expression artifact")
        }
        val expression = content.expression?.expression
            ?: return Result.Unsupported("v2 expression artifact is missing")
        if (GeneratorExpression.validateResolved(expression) !is GeneratorExpression.InputValidation.Valid) {
            return Result.Unsupported("expression artifact failed validation")
        }
        if (expression.expressionContractVersion == GeneratorExpression.EXPRESSION_CONTRACT_V3) {
            if (!capability.enabled) {
                // Blanket fail-closed until the caller supplies the registries.
                return Result.Unsupported("finished collage rendering is not supported")
            }
            val style = expression.style
                ?: return Result.Unsupported("expression style is not supported")
            val expectedDigest = capability.styleDigest(style.styleId, style.styleVersion)
                ?: return Result.Unsupported("expression style is not supported")
            if (!MessageDigest.isEqual(style.digest, expectedDigest)) {
                return Result.Unsupported("expression style is not supported")
            }
            if (expression.fontVersion != capability.fontVersion ||
                expression.paletteVersion != capability.paletteVersion
            ) {
                return Result.Unsupported("expression font or palette version is not supported")
            }
            val noteType = expression.noteType
            if (noteType == null ||
                capability.fontResource(noteType.family, noteType.weight, noteType.italic) == null
            ) {
                return Result.Unsupported("expression style is not supported")
            }
            expression.musicRegion?.let { music ->
                val type = music.type
                if (type == null ||
                    capability.fontResource(type.family, type.weight, type.italic) == null
                ) {
                    return Result.Unsupported("expression style is not supported")
                }
                val textOnly = music.mode == "text" && music.artwork == null
                val artworkOk = capability.artworkSupported &&
                    music.mode == "thumbnail" && music.artwork != null
                if (!textOnly && !artworkOk) {
                    return Result.Unsupported("music artwork rendering is not supported")
                }
            }
            if (!capability.maskSupported && expression.placements.any { it.maskId != null }) {
                return Result.Unsupported("expression mask is not supported")
            }
        }
        if (expression.expressionContractVersion == GeneratorExpression.EXPRESSION_CONTRACT_V4) {
            if (!capability.enabled) {
                return Result.Unsupported("finished collage rendering is not supported")
            }
            val style = expression.style
                ?: return Result.Unsupported("expression style is not supported")
            val expectedDigest = capability.styleDigest(style.styleId, style.styleVersion)
                ?: return Result.Unsupported("expression style is not supported")
            if (!MessageDigest.isEqual(style.digest, expectedDigest)) {
                return Result.Unsupported("expression style is not supported")
            }
            if (expression.fontVersion != capability.fontVersion ||
                expression.paletteVersion != capability.paletteVersion
            ) {
                return Result.Unsupported("expression font or palette version is not supported")
            }
            val noteType = expression.noteType
            if (noteType == null ||
                capability.fontResource(noteType.family, noteType.weight, noteType.italic) == null
            ) {
                return Result.Unsupported("expression style is not supported")
            }
            expression.provenance?.forEach { mark ->
                if (capability.fontResource(mark.family, mark.weight, mark.italic) == null) {
                    return Result.Unsupported("expression style is not supported")
                }
            }
            expression.musicRegion?.let { music ->
                val type = music.type
                if (type == null ||
                    capability.fontResource(type.family, type.weight, type.italic) == null
                ) {
                    return Result.Unsupported("expression style is not supported")
                }
                val textOnly = music.mode in GeneratorExpression.MUSIC_TEXT_ONLY_MODES_V4 &&
                    music.artwork == null
                val artworkOk = capability.artworkSupported &&
                    music.mode in GeneratorExpression.MUSIC_COVER_MODES_V4 &&
                    music.artwork != null
                val bareOk = music.mode in GeneratorExpression.MUSIC_BARE_TEXT_OK_MODES_V4 &&
                    music.artwork == null
                if (!textOnly && !artworkOk && !bareOk) {
                    return Result.Unsupported("music artwork rendering is not supported")
                }
            }
            if (!capability.maskSupported &&
                expression.placements.any { it.maskId != null || it.contentRect != null }
            ) {
                return Result.Unsupported("expression mask is not supported")
            }
        }
        if (expression.expressionContractVersion == GeneratorExpression.EXPRESSION_CONTRACT_V2) {
            val note = content.note?.takeIf { it.isNotEmpty() }
            if ((note == null) != (expression.noteRegion == null)) {
                return Result.Unsupported("expression note region does not match the manifest note")
            }
        }
        if (expression.placements.map { it.contentId } != expression.input.photos.map { it.contentId }) {
            return Result.Unsupported("expression placements are not in source order")
        }
        val photos = content.photos.sortedBy { it.ordinal }
        if (expression.input.photos.size != photos.size) {
            return Result.Unsupported("expression source count does not match the manifest photos")
        }
        if (expression.input.photos.map { it.ordinal } != photos.map { it.ordinal }) {
            return Result.Unsupported("expression source ordinals do not match the manifest photos")
        }
        return Result.Supported(expression)
    }
}
