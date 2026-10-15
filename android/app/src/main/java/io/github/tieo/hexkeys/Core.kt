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

    /** Outline corners as x0, y0, x1, y1, ... */
    external fun outline(handle: Long, index: Int): FloatArray

    /** Where the label goes, as x, y. */
    external fun labelPosition(handle: Long, index: Int): FloatArray
    external fun label(handle: Long, index: Int): String

    /** The key under a touch, or -1. */
    external fun keyAt(handle: Long, x: Float, y: Float): Int

    /** What a press means, as kind (commit, backspace, enter, redraw) and text. */
    external fun press(handle: Long, index: Int): Array<String>
}
