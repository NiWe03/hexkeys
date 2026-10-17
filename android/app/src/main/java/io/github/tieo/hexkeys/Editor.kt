package io.github.tieo.hexkeys

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent

/** Text that typing and deleting work on: the field typed in, or the emoji search's query. */
interface TextTarget {
    /** The text before the cursor, or before the selection when there is one. */
    fun textBefore(): String
    val hasSelection: Boolean
    fun selectedText(): String
    /** Puts `text` in place of the selection or at the cursor. */
    fun commit(text: String)
    /** Deletes `length` UTF-16 characters before the cursor. */
    fun deleteBefore(length: Int)
    fun beginBatch() {}
    fun endBatch() {}
}

/** The emoji search's query, typed at its end. */
class QueryText(private val query: StringBuilder) : TextTarget {
    override fun textBefore() = query.toString()
    override val hasSelection get() = false
    override fun selectedText() = ""
    override fun commit(text: String) {
        query.append(text)
    }
    override fun deleteBefore(length: Int) {
        query.setLength(maxOf(0, query.length - length))
    }
}

/**
 * The field being typed in, through the service's input connection, with a
 * copy of the text before the cursor kept up to date by every edit made
 * here. Reading text from the app waits for the app's main thread, which
 * took 10 to 70 ms a read in a browser, several times per key; the copy is
 * read again only when the cursor moves for some other reason, such as the
 * user tapping elsewhere in the text or the app changing it.
 */
class Editor(private val service: InputMethodService) : TextTarget {

    private val connection get() = service.currentInputConnection

    /** The text before the selection's start, its last [CONTEXT] characters or more; null when unknown. */
    private var before: String? = null

    /** Whether [before] reaches back to the start of the field. */
    private var complete = false

    /** The selection as the app last reported it, or as the last edit here left it. */
    var selectionStart = -1
        private set
    var selectionEnd = -1
        private set

    override val hasSelection get() = selectionStart != selectionEnd

    /** A new field, or the same one started over. */
    fun reset(start: Int, end: Int) {
        before = null
        selectionStart = start
        selectionEnd = end
    }

    /**
     * The app reports where the selection is now. Where an edit here put it,
     * the copy stays; anywhere else it is read again when next needed.
     */
    fun selectionChanged(start: Int, end: Int) {
        if (start != selectionStart || end != selectionEnd) before = null
        selectionStart = start
        selectionEnd = end
    }

    /** The text before the cursor, or before the selection when there is one. */
    override fun textBefore(): String {
        before?.let { return it }
        val read = connection?.getTextBeforeCursor(CONTEXT, 0)?.toString().orEmpty()
        before = read
        complete = read.length < CONTEXT
        return read
    }

    /** Up to `length` characters after the cursor, read from the app. */
    fun textAfter(length: Int): String = connection?.getTextAfterCursor(length, 0)?.toString().orEmpty()

    /** The selected text; empty without a selection, which needs no read. */
    override fun selectedText(): String =
        if (hasSelection) connection?.getSelectedText(0)?.toString().orEmpty() else ""

    /** Puts `text` in place of the selection or at the cursor. */
    override fun commit(text: String) {
        val known = before
        connection?.commitText(text, 1)
        val start = minOf(selectionStart, selectionEnd)
        selectionStart = start + text.length
        selectionEnd = selectionStart
        before = known?.let { trimmed(it + text) }
    }

    /** Deletes `length` UTF-16 characters before the cursor. */
    override fun deleteBefore(length: Int) {
        if (length <= 0) return
        connection?.deleteSurroundingText(length, 0)
        selectionStart = maxOf(0, selectionStart - length)
        selectionEnd = maxOf(0, selectionEnd - length)
        val known = before ?: return
        before = known.dropLast(length)
        // A copy cut short at the front could run out before the field does.
        if (!complete && before!!.length < CONTEXT) before = null
    }

    /** Deletes `count` code points before the cursor. */
    fun deleteCodePointsBefore(count: Int) {
        val text = textBefore()
        val start = runCatching { text.offsetByCodePoints(text.length, -count) }.getOrDefault(0)
        deleteBefore(text.length - start)
    }

    override fun beginBatch() {
        connection?.beginBatchEdit()
    }

    override fun endBatch() {
        connection?.endBatchEdit()
    }

    /**
     * Moves the cursor `dx` characters right and `dy` lines down. Sideways it
     * is placed directly and stops at the text's ends: an arrow key pressed
     * there moves the focus out of the field instead, which closes the
     * keyboard. Up and down go by arrow keys, only while there is text that way.
     */
    fun moveCursor(dx: Int, dy: Int) {
        val connection = connection ?: return
        if (dx != 0) {
            val room = if (dx > 0) {
                connection.getTextAfterCursor(dx, 0)?.length ?: 0
            } else {
                minOf(-dx, maxOf(selectionStart, 0))
            }
            val target = maxOf(0, selectionStart) + if (dx > 0) room else -room
            if (room > 0) {
                connection.setSelection(target, target)
                selectionStart = target
                selectionEnd = target
                before = null
            }
        }
        if (dy != 0) {
            val textThatWay = if (dy > 0) connection.getTextAfterCursor(1, 0) else connection.getTextBeforeCursor(1, 0)
            if (!textThatWay.isNullOrEmpty()) {
                repeat(kotlin.math.abs(dy)) { sendKey(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
            }
        }
    }

    /** Sends a key to the app, which changes the text in its own way. */
    fun sendKey(code: Int) {
        before = null
        service.sendDownUpKeyEvents(code)
    }

    /** Keeps the copy from growing without end, which marks it as no longer reaching the field's start. */
    private fun trimmed(text: String): String {
        if (text.length <= KEEP) return text
        complete = false
        return text.takeLast(KEEP)
    }

    companion object {
        /** Enough text before the cursor for the word being typed and the one before it. */
        const val CONTEXT = 64

        /** The most kept in the copy. */
        private const val KEEP = 256

        val ENTER = KeyEvent.KEYCODE_ENTER
    }
}
