package org.futo.inputmethod.latin.uix.actions

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action

/**
 * Shared selection (shift) state. Toggled by [SelectionModeAction] in the action bar and by the
 * shift key in the Text Editor action. While enabled, the arrow actions extend the selection.
 */
val SelectionModeState = mutableStateOf(false)

val ArrowUpAction = Action(
    icon = R.drawable.chevron_up,
    name = R.string.action_cursor_up_title,
    simplePressImpl = { manager, _ ->
        manager.cursorVertical(up = true, select = SelectionModeState.value)
    },
    windowImpl = null,
)

val ArrowDownAction = Action(
    icon = R.drawable.chevron_down,
    name = R.string.action_cursor_down_title,
    simplePressImpl = { manager, _ ->
        manager.cursorVertical(up = false, select = SelectionModeState.value)
    },
    windowImpl = null,
)

val ArrowLeftAction = Action(
    icon = R.drawable.chevron_left,
    name = R.string.action_cursor_left_title,
    simplePressImpl = { manager, _ ->
        if(SelectionModeState.value) {
            manager.cursorLeft(1, stepOverWords = false, select = true)
        } else {
            manager.sendKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        }
    },
    windowImpl = null,
)

val ArrowRightAction = Action(
    icon = R.drawable.chevron_right,
    name = R.string.action_cursor_right_title,
    simplePressImpl = { manager, _ ->
        if(SelectionModeState.value) {
            manager.cursorRight(1, stepOverWords = false, select = true)
        } else {
            manager.sendKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        }
    },
    windowImpl = null,
)

val SelectionModeAction = Action(
    icon = R.drawable.shift,
    name = R.string.action_selection_mode_title,
    simplePressImpl = { _, _ ->
        SelectionModeState.value = !SelectionModeState.value
    },
    windowImpl = null,
    toggledState = SelectionModeState,
)
