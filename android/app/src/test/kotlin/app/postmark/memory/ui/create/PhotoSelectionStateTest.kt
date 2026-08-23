package app.postmark.memory.ui.create

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSelectionStateTest {

    private fun ids(n: Int) = (1..n).map { "picker-item-$it" }

    @Test
    fun emptyStateCannotProceed() {
        val state = PhotoSelectionState()
        assertFalse(state.canProceed)
    }

    @Test
    fun threeSelectedEnableProceed() {
        val state = PhotoSelectionState()
        ids(3).forEach { state.toggle(it) }
        assertTrue(state.canProceed)
        assertEquals(3, state.selectedIds.size)
    }

    @Test
    fun sixthPhotoIsRejectedAtLimit() {
        val state = PhotoSelectionState()
        ids(5).forEach { state.toggle(it) }
        val result = state.toggle("picker-item-6")
        assertEquals(PhotoSelectionState.ToggleResult.RejectedAtLimit, result)
        assertEquals(5, state.selectedIds.size)
        assertTrue(state.isAtLimit)
    }

    @Test
    fun toggleRemovesExistingId() {
        val state = PhotoSelectionState()
        ids(4).forEach { state.toggle(it) }
        val result = state.toggle("picker-item-2")
        assertEquals(PhotoSelectionState.ToggleResult.Removed(3), result)
        assertFalse(state.selectedIds.contains("picker-item-2"))
        assertTrue(state.canProceed) // 3 still inside window
    }

    @Test
    fun removalBelowThreeDisablesProceed() {
        val state = PhotoSelectionState()
        ids(3).forEach { state.toggle(it) }
        state.remove("picker-item-1")
        state.remove("picker-item-2")
        assertFalse(state.canProceed)
        assertEquals(listOf("picker-item-3"), state.selectedIds)
    }

    @Test
    fun clearResetsEverything() {
        val state = PhotoSelectionState()
        ids(5).forEach { state.toggle(it) }
        state.clear()
        assertEquals(emptyList<String>(), state.selectedIds.toList())
        assertFalse(state.canProceed)
        assertFalse(state.isAtLimit)
    }

    @Test
    fun selectionOrderIsPreservedForManifestOrdinalMapping() {
        val state = PhotoSelectionState()
        listOf("c", "a", "b").forEach { state.toggle(it) }
        assertEquals(listOf("c", "a", "b"), state.selectedIds.toList())
    }
}
