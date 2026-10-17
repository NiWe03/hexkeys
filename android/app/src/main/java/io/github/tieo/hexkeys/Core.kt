package io.github.tieo.hexkeys

/** The Rust keyboard core. Every function takes the handle from [create]. */
object Core {
    init {
        System.loadLibrary("hexkeys_jni")
    }

    /**
     * A keyboard fitted to [width] pixels; [stretch] makes cells taller than
     * regular hexagons, and a finger held still for [longPressMs] is a long press.
     * [space_middle] puts space and the marks between the letters instead of
     * below them; [editKeys] adds shift and backspace keys.
     */
    external fun create(width: Float, stretch: Float, longPressMs: Long, space_middle: Boolean, editKeys: Boolean): Long
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

    /** The QWERTZ row of the key's letter, 0 to 2, which themes colour it by; -1 for other keys. */
    external fun keyRow(handle: Long, index: Int): Int

    /** The key the finger went down on, or -1. */
    external fun pressed(handle: Long): Int

    /** Touches with their [android.os.SystemClock.uptimeMillis] time. */
    external fun touchDown(handle: Long, x: Float, y: Float, time: Long)
    external fun touchMove(handle: Long, x: Float, y: Float, time: Long): Array<String>

    /** Delete swipes from the left edge instead of the right; the capital gesture as 0 flick up, 1 hold. */
    external fun configure(handle: Long, deleteFromLeft: Boolean, capital: Int)

    /** What lifting means, as kind (commit, replace, backspace, enter, redraw, nothing) and text; replace has the old and new text split by a tab. */
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
    /** The choice lifting the finger takes, or -1 for none. */
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

    /** Counts [word] as kept [times] more times; returns how often it now has been. */
    external fun lexiconLearn(lexicon: Long, word: String, times: Int): Int

    /** Drops every learned word. */
    external fun lexiconForget(lexicon: Long)

    /**
     * The suggestion strip for the text before the cursor: left, middle and
     * right word (empty when unused), how many code points a choice replaces,
     * the autocorrection, and the typed word when it is not a word yet
     * (both empty when none).
     */
    external fun strip(lexicon: Long, keyboard: Long, before: String): Array<String>

    /** The path being swiped as x0, y0, x1, y1, ..., empty when none is. */
    external fun trail(handle: Long): FloatArray

    /** The words the last swipe spells, best first, after the text before the cursor. */
    external fun swipe(lexicon: Long, keyboard: Long, before: String): Array<String>

    /** The accented letters of the lexicon's languages, as "a:ä;o:ö"; slow, call it off the main thread. */
    external fun lexiconAccents(lexicon: Long): String

    /** Sets what holding a letter offers, in the form [lexiconAccents] returns. */
    external fun setAccents(handle: Long, accents: String)

    /** What the capital gesture types on a punctuation key, shown small on it; empty for other keys. */
    external fun keyHint(handle: Long, index: Int): String

    /** Loads a whisper model file; 0 when it cannot be loaded. */
    external fun voiceLoad(path: String): Long

    external fun voiceFree(model: Long)

    /** The text spoken in 16 kHz mono samples, in the likeliest of the languages (ISO codes, comma separated). */
    external fun voiceTranscribe(model: Long, audio: FloatArray, languages: String): String
}
