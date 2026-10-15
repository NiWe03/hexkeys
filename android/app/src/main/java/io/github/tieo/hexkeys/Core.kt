package io.github.tieo.hexkeys

/** The Rust keyboard core. Every function takes the handle from [create]. */
object Core {
    init {
        System.loadLibrary("hexkeys_jni")
    }

    /** A keyboard fitted to [width] pixels; [stretch] makes cells taller than regular hexagons. */
    external fun create(width: Float, stretch: Float): Long
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

    /** The key under a touch, or -1. */
    external fun keyAt(handle: Long, x: Float, y: Float): Int

    /** What a press means, as kind (commit, backspace, enter, redraw) and text. */
    external fun press(handle: Long, index: Int): Array<String>

    /** Room above the keys a long-press popup can reach into. */
    external fun headroom(handle: Long): Float

    /** Opens the key's long-press popup; false when it has no alternatives. */
    external fun longPress(handle: Long, index: Int): Boolean
    external fun popupMove(handle: Long, x: Float, y: Float)

    /** Commits the selected alternative, in the same form as [press]. */
    external fun popupRelease(handle: Long): Array<String>
    external fun popupCancel(handle: Long)

    /** Choices in the open popup, 0 when none is open. */
    external fun popupCount(handle: Long): Int
    external fun popupSelected(handle: Long): Int
    external fun popupOutline(handle: Long, index: Int): FloatArray
    external fun popupLabelPosition(handle: Long, index: Int): FloatArray
    external fun popupLabel(handle: Long, index: Int): String
}
