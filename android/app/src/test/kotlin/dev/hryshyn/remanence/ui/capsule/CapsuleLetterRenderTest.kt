package dev.hryshyn.remanence.ui.capsule

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hryshyn.remanence.core.model.GeneratorExpression as G
import dev.hryshyn.remanence.ui.collage.StyleRegistry
import dev.hryshyn.remanence.ui.hold.HoldTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The letter on the back, drawn by the real host with the real renderer and
 * fonts (ADR-021 §2). Native-graphics review images under build/letter-review;
 * NOT a device proof. The site's `stageLetterCheck.mjs` is the same check.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleLetterRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val front = "Mum, we made it to the sea at last. The water is cold and the kids will not leave it."
    private val whole = "$front\n\nWe took the long road through the hills, the one you always wanted to see. " +
        "There is a small white church at the top and a bench beside it; we sat there for an hour and nobody " +
        "said a word.\n\nI will tell you the rest when we are home. Keep the card on the fridge until then."

    private fun plain(): G.ResolvedExpression {
        val base = mediaExpression(3, hasTrack = false)
        return base.copy(input = base.input.copy(note = front), noteRegion = G.NoteRegion(16, 240, 328, 60))
    }

    /** "proof": dark paper, and a light mount that carries the photos and the note in dark ink. */
    private fun mounted(): G.ResolvedExpression {
        val base = mediaExpression(4, hasTrack = false)
        val style = requireNotNull(StyleRegistry.style("proof"))
        return base.copy(
            grammarId = "18",
            input = base.input.copy(note = front),
            noteRegion = G.NoteRegion(16, 240, 328, 60),
            style = G.StyleBinding(style.id, style.version, requireNotNull(StyleRegistry.digest(style.id))),
            decor = listOf(G.SceneDecor(G.DecorKind.CARRIER, 6, 5, 349, 627)),
        )
    }

    private fun loaded(letter: String?, expression: G.ResolvedExpression = plain()): CollagePresentationState {
        val photos = expression.input.photos.associate { p ->
            p.contentId to mediaPng(p.widthPx, p.heightPx, mediaPhotoColors[p.ordinal])
        }
        return CollagePresentationState(expression, loadPhoto = { photos.getValue(it).copyOf() }, note = front, letter = letter)
            .also { runBlocking { it.load() } }
    }

    @Test
    fun arrowSitsOnBarePaperInsideTheNoteAndTheNoteTurnsTheCard() {
        var state by mutableStateOf(loaded(letter = null))
        rule.setContent { HoldTheme { CollagePresentationHost(state, "Synthetic postcard", {}) } }
        rule.onNodeWithTag("capsule_letter_mark").assertDoesNotExist()
        val plain = shot("letter-none")

        rule.runOnIdle { state = loaded(letter = whole) }
        val note = rule.onNodeWithTag("capsule_letter_mark").fetchSemanticsNode().boundsInRoot
        val sheet = rule.onNodeWithTag("capsule_sheet").fetchSemanticsNode().boundsInRoot
        val scale = sheet.width / 360f
        val withMark = shot("letter-front")

        // The letter changes the front by the arrow only: everything that
        // differs from the same card without a letter is one small square
        // inside the note, drawn where that card had bare paper.
        var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = -1; var bottom = -1
        val under = mutableSetOf<Int>()
        var changed = 0
        for (y in 0 until plain.height) for (x in 0 until plain.width) {
            if (plain.getPixel(x, y) == withMark.getPixel(x, y)) continue
            changed++
            under += plain.getPixel(x, y)
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
        }
        assertTrue("the arrow is drawn ($changed px)", changed > 30)
        assertTrue(
            "the arrow stays inside the note: $left,$top..$right,$bottom in $note",
            left >= note.left - 1 && top >= note.top - 1 && right <= note.right + 1 && bottom <= note.bottom + 1,
        )
        val side = 18 * scale + 2
        assertTrue("the arrow is at most 18 canvas px: ${right - left + 1}x${bottom - top + 1}", right - left + 1 <= side && bottom - top + 1 <= side)
        assertEquals("the arrow covers no painted pixel: ${under.map(Integer::toHexString)}", 1, under.size)
        mediaPhotoColors.forEach { c -> assertTrue("photos on the front", count(withMark, c) > 20) }

        rule.onNodeWithTag("capsule_letter_mark").performClick()
        rule.onNodeWithTag("capsule_letter_text").assertTextEquals(whole)
        val back = shot("letter-back")
        mediaPhotoColors.forEach { c -> assertEquals("no photo shows through the back", 0, count(back, c)) }

        rule.onNodeWithTag("capsule_letter_front").performClick()
        rule.onNodeWithTag("capsule_letter_back").assertDoesNotExist()
        val again = shot("letter-front-again")
        assertTrue("turning back shows the same front", again.sameAs(withMark))
        File(output(), "letter-facts.txt").writeText(
            "sheet=$sheet scale=$scale\nnote=$note\narrow=$left,$top..$right,$bottom changed=$changed\n",
        )
    }

    @Test
    fun aNoteOnAMountTurnsToThatMountNotToThePaperBehindIt() {
        val paper = 0xFF333633.toInt()
        val mount = 0xFFF8F7EF.toInt()
        val ink = 0xFF202621.toInt()
        rule.setContent { HoldTheme { CollagePresentationHost(loaded(whole, mounted()), "Synthetic postcard", {}) } }
        val sheet = rule.onNodeWithTag("capsule_sheet").fetchSemanticsNode().boundsInRoot
        val front = shot("letter-mount-front")
        assertTrue("the front shows the paper around its mount", count(front, paper) > 100)
        assertTrue("and the note's ink on the mount", count(front, ink) > 30)

        rule.onNodeWithTag("capsule_letter_mark").performClick()
        rule.onNodeWithTag("capsule_letter_text").assertTextEquals(whole)
        val back = shot("letter-mount-back")
        val inside = Bitmap.createBitmap(
            back, sheet.left.toInt() + 2, sheet.top.toInt() + 2, sheet.width.toInt() - 4, sheet.height.toInt() - 4,
        )
        // Dark ink on the dark paper would be unreadable; the back is the mount the note was written on.
        assertEquals("no dark paper on the back", 0, count(inside, paper))
        assertTrue("the back is the mount", count(inside, mount) > inside.width * inside.height / 2)
        assertTrue("in the note's ink", count(inside, ink) > 100)
    }

    private fun count(bitmap: Bitmap, color: Int): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { it == color }
    }

    private fun output() = File("build/letter-review").apply { mkdirs() }

    private fun shot(name: String): Bitmap {
        rule.waitForIdle()
        return rule.runOnIdle {
            // PixelCopy has no real Surface producer on Robolectric: draw the view tree itself.
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(output(), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap
        }
    }
}
