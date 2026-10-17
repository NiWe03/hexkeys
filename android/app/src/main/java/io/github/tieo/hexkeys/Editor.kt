package io.github.tieo.hexkeys

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent

/**
 * The field being typed in, through the service's input connection, with a
 * copy of the text before the cursor kept up to date by every edit made
 * here. Reading text from the app waits for the app's main thread, which
 * took 10 to 70 ms a read in a browser, several times per key; the copy is
 * read again only when the cursor moves for some other reason, such as the
 * user tapping elsewhere in the text or the app changing it.
 */
class Editor(private val service: InputMethodService) {

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

    val hasSelection get() = selectionStart != selectionEnd

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
    fun textBefore(): String {
        before?.let { return it }
        val read = connection?.getTextBeforeCursor(CONTEXT, 0)?.toString().orEmpty()
        before = read
        complete = read.length < CONTEXT
        return read
    }

    /** The selected text; empty without a selection, which needs no read. */
    fun selectedText(): String =
        if (hasSelection) connection?.getSelectedText(0)?.toString().orEmpty() else ""

    /** Puts `text` in place of the selection or at the cursor. */
    fun commit(text: String) {
        val known = before
        connection?.commitText(text, 1)
        val start = minOf(selectionStart, selectionEnd)
        selectionStart = start + text.length
        selectionEnd = selectionStart
        before = known?.let { trimmed(it + text) }
    }

    /** Deletes `length` UTF-16 characters before the cursor. */
    fun deleteBefore(length: Int) {
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

    fun finishComposing() {
        connection?.finishComposingText()
    }

    fun markComposing(start: Int, end: Int) {
        connection?.setComposingRegion(start, end)
    }

    fun beginBatch() {
        connection?.beginBatchEdit()
    }

    fun endBatch() {
        connection?.endBatchEdit()
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
