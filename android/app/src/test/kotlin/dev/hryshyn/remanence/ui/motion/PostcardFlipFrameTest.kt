package dev.hryshyn.remanence.ui.motion

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostcardFlipFrameTest {
    private val screen = Size(1080f, 2200f)
    private val card = OpeningRect(240f, 600f, 840f, 1020f)

    @Test fun startsOnTheCardFaceUp() {
        val frame = flipFrame(0f, card, Rect(0f, 0f, 1080f, 2200f), screen)
        assertEquals(0f, frame.angle, 0f)
        assertEquals(240f, frame.rect.left, 0.5f)
        assertEquals(600f, frame.rect.top, 0.5f)
    }

    @Test fun turnsOverMidwayAndEndsFullScreenAndDeveloped() {
        assertTrue(flipFrame(0.20f, card, null, screen).angle in 60f..150f)
        val end = flipFrame(1f, card, null, screen)
        assertEquals(180f, end.angle, 0f)
        assertEquals(Rect(0f, 0f, 1080f, 2200f), end.rect)
        assertEquals(1f, end.expand, 0f)
        assertEquals(1f, end.develop, 0f)
        assertEquals(1f, end.coverScale(screen), 0f)
    }

    @Test fun cardKeepsItsProportionsWhileTurning() {
        val mid = flipFrame(0.40f, card, null, screen)
        assertEquals(600f / 420f, mid.rect.width / mid.rect.height, 0.01f)
    }

    @Test fun cardIsMeasuredFromTheContainerOrigin() {
        val frame = flipFrame(0f, card, Rect(0f, 100f, 1080f, 2300f), screen)
        assertEquals(500f, frame.rect.top, 0.5f)
    }

    @Test fun hapticBeatsFollowTheTurnInOrder() {
        val stages = (0..100).map { openingHapticStage(it / 100f) }
        assertEquals(listOf(0, 1, 2, 3), stages.distinct())
        assertEquals(stages, stages.sorted())
        assertEquals(3, openingHapticStage(1f))
    }
}
