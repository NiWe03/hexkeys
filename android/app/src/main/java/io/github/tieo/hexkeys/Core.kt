package io.github.tieo.hexkeys

/** The Rust keyboard core. Every function takes the handle from [create]. */
object Core {
    init {
        System.loadLibrary("hexkeys_jni")
    }

    /**
     * A keyboard fitted to [width] pixels; [stretch] makes cells taller than
     * regular hexagons, and a finger held still for [longPressMs] is a long press.
     */
    external fun create(width: Float, stretch: Float, longPressMs: Long): Long
    external fun destroy(handle: Long)
    external fun height(handle: Long): Float
    external fun keyCount(handle: Long): Int

    /** Width of one cell, which labels are sized from. */
    external fun cellWidth(handle: Long): Float

    /** Outline corners as x0, y0, x1, y1, ... */
    external fun outline(handle: Long, index: Int): FloatArray

    /** Where the label goes, as x, y. */
    external fun labelPosition(handle: Long, index: Int): FloatArray
    external fun label(handle: Long, index: Int): String

    /** The key the finger went down on, or -1. */
    external fun pressed(handle: Long): Int

    /** Touches with their [android.os.SystemClock.uptimeMillis] time. */
    external fun touchDown(handle: Long, x: Float, y: Float, time: Long)
    external fun touchMove(handle: Long, x: Float, y: Float, time: Long)

    /** What lifting means, as kind (commit, backspace, enter, redraw, nothing) and text. */
    external fun touchUp(handle: Long, x: Float, y: Float, time: Long): Array<String>
    external fun touchCancel(handle: Long)

    /** When [tick] is next due, or -1. */
    external fun deadline(handle: Long): Long

    /** What a hold that lasted long enough does, in the same form as [touchUp]. */
    external fun tick(handle: Long, time: Long): Array<String>

    /** Room above the keys a long-press popup can reach into. */
    external fun headroom(handle: Long): Float

    /** Choices in the open popup, 0 when none is open. */
    external fun popupCount(handle: Long): Int
    external fun popupSelected(handle: Long): Int
    external fun popupOutline(handle: Long, index: Int): FloatArray
    external fun popupLabelPosition(handle: Long, index: Int): FloatArray
    external fun popupLabel(handle: Long, index: Int): String

    /** Dictionaries for suggestions, independent of any keyboard handle. */
    external fun lexiconCreate(): Long
    external fun lexiconDestroy(lexicon: Long)

    /** Adds a `.dict` file's bytes; false when they are not a readable dictionary. */
    external fun lexiconAdd(lexicon: Long, bytes: ByteArray): Boolean
    external fun lexiconClear(lexicon: Long)

    /**
     * The suggestion strip for the text before the cursor: left, middle and
     * right word (empty when unused), then how many code points a choice replaces.
     */
    external fun strip(lexicon: Long, keyboard: Long, before: String): Array<String>
}
