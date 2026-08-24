package app.postmark.memory.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Preparation items of the physical postcard that MUST be complete before
 * the back side may be captured (docs/recognition.md section 3, product.md
 * sender flow step 6).
 */
enum class PreparedBackItem {
    MESSAGE_WRITTEN,
    ADDRESS_WRITTEN,
    SIGNATURE_ADDED,
    POSTAGE_APPLIED,
}

/**
 * Pure gate for the prepared-back checklist (docs/implementation-plan.md
 * M1-R18). The back-capture action stays locked until every item is
 * explicitly confirmed; unchecking any item locks it again.
 */
class PreparedBackGate {

    var checked: Map<PreparedBackItem, Boolean> by mutableStateOf(
        PreparedBackItem.entries.associateWith { false },
    )
        private set

    val ready: Boolean
        get() = checked.values.all { it }

    fun setChecked(item: PreparedBackItem, value: Boolean) {
        require(item in checked) { "unknown checklist item" }
        checked = checked + (item to value)
    }
}
