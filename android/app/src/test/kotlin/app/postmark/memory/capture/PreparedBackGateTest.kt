package app.postmark.memory.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure gate proof for M1-R18: locked until every item is confirmed. */
class PreparedBackGateTest {

    private fun allCheckedExcept(vararg unchecked: PreparedBackItem): Map<PreparedBackItem, Boolean> =
        PreparedBackItem.entries.associate { it to (it !in unchecked) }

    @Test
    fun startsLockedWithNothingConfirmed() {
        val gate = PreparedBackGate()
        assertFalse(gate.ready)
        PreparedBackItem.entries.forEach { assertFalse(gate.checked[it] == true) }
    }

    @Test
    fun staysLockedUntilEveryItemIsConfirmed() {
        val gate = PreparedBackGate()
        val others = PreparedBackItem.entries.toTypedArray()

        // Confirm items one by one; the last one flips the gate.
        var index = 0
        while (index < others.size - 1) {
            gate.setChecked(others[index], true)
            assertFalse("gate must stay locked with ${others.size - index - 1} steps left", gate.ready)
            index += 1
        }
        gate.setChecked(others.last(), true)
        assertTrue(gate.ready)
    }

    @Test
    fun uncheckingAnyItemLocksTheGateAgain() {
        val gate = PreparedBackGate()
        PreparedBackItem.entries.forEach { gate.setChecked(it, true) }
        assertTrue(gate.ready)

        gate.setChecked(PreparedBackItem.POSTAGE_APPLIED, false)
        assertFalse(gate.ready)

        gate.setChecked(PreparedBackItem.POSTAGE_APPLIED, true)
        assertTrue(gate.ready)
    }

    @Test
    fun unknownItemsAreRejected() {
        try {
            PreparedBackGate().setChecked(PreparedBackItem.MESSAGE_WRITTEN, true)
        } catch (unexpected: Exception) {
            fail("known item must be accepted: $unexpected")
        }
        assertEquals(
            PreparedBackItem.entries.size,
            PreparedBackGate().checked.size,
        )
    }
}
