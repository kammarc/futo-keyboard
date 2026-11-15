package org.futo.inputmethod.latin.uix

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection

/**
 * Moves the cursor up/down by sending DPAD_UP/DPAD_DOWN, but prevents the standard Android text
 * field behavior of jumping to the very start (when pressing up on the first line) or the very end
 * (when pressing down on the last line). In that case the cursor/selection stays where it was.
 *
 * Moves are serialized: the next move is only sent once the editor has reported the result of
 * the previous one, so that each result can be checked against the position it started from.
 */
class VerticalCursorMover(
    private val getInputConnection: () -> InputConnection?,
    private val sendKeyEvent: (keyCode: Int, metaState: Int) -> Unit,
) {
    private data class Pending(
        val up: Boolean,
        val select: Boolean,
        val prevSelStart: Int,
        val prevSelEnd: Int,
        // Top of the line the cursor was on, if the editor reports CursorAnchorInfo
        val prevLineTop: Float?,
        // Set once the cursor has landed on the start/end of the text and we're waiting for
        // CursorAnchorInfo to tell us whether it actually changed line
        var awaitingAnchorAt: Int? = null,
    )

    private val handler = Handler(Looper.getMainLooper())

    private var selStart = -1
    private var selEnd = -1
    private var lastAnchorInfo: CursorAnchorInfo? = null

    private var pending: Pending? = null
    private var queuedUp = 0
    private var queuedDown = 0

    private val timeout = Runnable {
        // No (decisive) update from the editor, e.g. cursor was already at the edge and didn't move
        pending?.let { p ->
            if(p.awaitingAnchorAt != null) restore(p)
        }
        pending = null
        queuedUp = 0
        queuedDown = 0
    }

    fun onStartInput() {
        reset()
        lastAnchorInfo = null
        getInputConnection()?.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE
        )
    }

    fun reset() {
        handler.removeCallbacks(timeout)
        pending = null
        queuedUp = 0
        queuedDown = 0
    }

    fun move(up: Boolean, select: Boolean) {
        if(pending != null) {
            // Queue it up; if the direction reverses, the moves cancel out
            if(up) {
                if(queuedDown > 0) queuedDown-- else queuedUp++
            } else {
                if(queuedUp > 0) queuedUp-- else queuedDown++
            }
            return
        }

        sendMove(up, select)
    }

    private fun sendMove(up: Boolean, select: Boolean) {
        val meta = if(select) KeyEvent.META_SHIFT_ON else 0
        val keyCode = if(up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN

        if(selStart < 0 || selEnd < 0) {
            // Unknown cursor position, can't guard
            sendKeyEvent(keyCode, meta)
            return
        }

        pending = Pending(
            up = up,
            select = select,
            prevSelStart = selStart,
            prevSelEnd = selEnd,
            prevLineTop = lineTopOfCursor(lastAnchorInfo, selStart, selEnd),
        )

        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MS)

        sendKeyEvent(keyCode, meta)
    }

    fun onUpdateSelection(newSelStart: Int, newSelEnd: Int) {
        selStart = newSelStart
        selEnd = newSelEnd

        val p = pending ?: return
        if(newSelStart == p.prevSelStart && newSelEnd == p.prevSelEnd) return

        // The end of the selection that moved
        val moved = if(newSelEnd != p.prevSelEnd) newSelEnd else newSelStart
        val prevMoved = if(newSelEnd != p.prevSelEnd) p.prevSelEnd else p.prevSelStart

        if(!landedOnTextEdge(p, moved, prevMoved)) {
            finish()
            return
        }

        // Landed on the start/end of the text without crossing a line break. Either it jumped
        // there because there was no line above/below, or it was a regular move to the
        // first/last line. Only CursorAnchorInfo can tell these apart.
        val anchorInfo = lastAnchorInfo
        if(!p.select && p.prevLineTop != null) {
            val currTop = lineTopOfCursor(anchorInfo, newSelStart, newSelEnd)
            if(currTop != null) {
                if(currTop == p.prevLineTop) restore(p)
                finish()
            } else {
                p.awaitingAnchorAt = moved
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, TIMEOUT_MS)
            }
        } else {
            // No line information (or selecting, where the editor reports the line of the
            // selection anchor rather than the moving end): assume it jumped
            restore(p)
            finish()
        }
    }

    fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        lastAnchorInfo = info

        val p = pending ?: return
        if(p.awaitingAnchorAt == null) return

        val currTop = lineTopOfCursor(info, selStart, selEnd) ?: return
        if(currTop == p.prevLineTop) restore(p)
        finish()
    }

    private fun landedOnTextEdge(p: Pending, moved: Int, prevMoved: Int): Boolean {
        val ic = getInputConnection() ?: return false

        // Text between the previous and the new position (cursor is now at the new position)
        val between = if(p.up) {
            if(moved != 0 || prevMoved <= 0) return false
            ic.getTextAfterCursor(prevMoved.coerceAtMost(MAX_LINE_CHARS), 0)
        } else {
            if(moved <= prevMoved) return false
            val after = ic.getTextAfterCursor(1, 0)
            if(after == null || after.isNotEmpty()) return false
            ic.getTextBeforeCursor((moved - prevMoved).coerceAtMost(MAX_LINE_CHARS), 0)
        } ?: return false

        // If the move crossed a line break, there definitely was a line in that direction
        if(between.contains('\n')) return false

        // Too far to be a jump within a single line
        if((moved - prevMoved).let { if(it < 0) -it else it } > MAX_LINE_CHARS) return false

        return true
    }

    private fun restore(p: Pending) {
        getInputConnection()?.setSelection(p.prevSelStart, p.prevSelEnd)
        // Nothing more in this direction
        if(p.up) queuedUp = 0 else queuedDown = 0
    }

    private fun finish() {
        handler.removeCallbacks(timeout)
        val prev = pending
        pending = null

        val select = prev?.select ?: false
        when {
            queuedUp > 0 -> { queuedUp--; sendMove(true, select) }
            queuedDown > 0 -> { queuedDown--; sendMove(false, select) }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 300L
        private const val MAX_LINE_CHARS = 1000

        private fun lineTopOfCursor(info: CursorAnchorInfo?, selStart: Int, selEnd: Int): Float? {
            if(info == null) return null
            if(info.selectionStart != selStart || info.selectionEnd != selEnd) return null
            if(selStart != selEnd) return null
            val top = info.insertionMarkerTop
            return if(top.isNaN()) null else top
        }
    }
}
