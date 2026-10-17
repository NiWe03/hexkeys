package io.github.tieo.hexkeys

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/** The input method: hands the keyboard view the app it types into. */
class HexInputMethod : InputMethodService() {

    private var keyboard: KeyboardView? = null
    private var lexicon = 0L
    /** Off in password fields and where the app asks for no suggestions. */
    private var suggesting = false

    /** A correction autocorrect just made: what was typed, and the word and separator put in its place. */
    private data class Correction(val typed: String, val inserted: String)
    private var lastCorrection: Correction? = null

    /** The word a swipe just put in, while its alternatives show in the strip. */
    private var swiped: String? = null

    /** Reloads the dictionaries when the languages change in settings. */
    private val settingsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Dictionaries.ENABLED -> loadDictionaries()
            Theme.KEY -> keyboard?.theme = Theme.current(this)
            KeyboardView.LAYOUT -> keyboard?.requestLayout()
            Dictionaries.LEARNED_RESET -> {
                Core.lexiconForget(lexicon)
                learned.clear()
            }
        }
    }

    /** The learned words as last saved, mirrored in the lexicon. */
    private val learned = mutableMapOf<String, Int>()

    override fun onCreate() {
        super.onCreate()
        lexicon = Core.lexiconCreate()
        loadDictionaries()
        for ((word, times) in Dictionaries.learned(this)) {
            learned[word] = Core.lexiconLearn(lexicon, word, times)
        }
        Dictionaries.preferences(this).registerOnSharedPreferenceChangeListener(settingsChanged)
    }

    override fun onDestroy() {
        super.onDestroy()
        Dictionaries.preferences(this).unregisterOnSharedPreferenceChangeListener(settingsChanged)
        Core.lexiconDestroy(lexicon)
        lexicon = 0L
    }

    private fun loadDictionaries() {
        Core.lexiconClear(lexicon)
        for (file in Dictionaries.enabled(this)) {
            Dictionaries.read(this, file)?.let { Core.lexiconAdd(lexicon, it) }
        }
    }

    override fun onCreateInputView(): View = KeyboardView(
        this,
        onAction = { kind, text ->
            val connection = currentInputConnection ?: return@KeyboardView
            val undo = lastCorrection
            lastCorrection = null
            val justSwiped = swiped
            swiped = null
            when (kind) {
                "swipe" -> {
                    val view = keyboard ?: return@KeyboardView
                    if (!suggesting) return@KeyboardView
                    val before = connection.getTextBeforeCursor(CONTEXT_LENGTH, 0)?.toString().orEmpty()
                    val words = view.swipe(lexicon, before)
                    if (words.isEmpty()) return@KeyboardView
                    // Swiped words are put in with a space between them, as on other keyboards.
                    val last = before.lastOrNull()
                    val space = last != null && !last.isWhitespace() && last !in OPENING
                    swiped = words[0]
                    connection.commitText((if (space) " " else "") + words[0], 1)
                    view.showSwipe(words)
                }
                "commit" -> {
                    val correction = keyboard?.correction
                    if (text in SEPARATORS && correction != null &&
                        Dictionaries.preferences(this).getBoolean(Dictionaries.AUTOCORRECT, false)
                    ) {
                        val (word, replaces) = correction
                        val typed = connection.getTextBeforeCursor(CONTEXT_LENGTH, 0).toString()
                            .let { it.substring(it.offsetByCodePoints(it.length, -replaces)) }
                        connection.beginBatchEdit()
                        connection.deleteSurroundingTextInCodePoints(replaces, 0)
                        connection.commitText(word + text, 1)
                        connection.endBatchEdit()
                        lastCorrection = Correction(typed, word + text)
                    } else {
                        if (text in SEPARATORS) keyboard?.unknown?.let { learn(it, 1) }
                        connection.commitText(text, 1)
                    }
                }
                "backspace" -> {
                    val selected = connection.getSelectedText(0)
                    val before = connection.getTextBeforeCursor(CONTEXT_LENGTH, 0)?.toString().orEmpty()
                    when {
                        !selected.isNullOrEmpty() -> connection.commitText("", 1)
                        // Right after a swipe, backspace takes the whole word back.
                        justSwiped != null && before.endsWith(justSwiped) ->
                            connection.deleteSurroundingText(justSwiped.length, 0)
                        // Right after a correction, backspace puts back what was typed.
                        undo != null && before.endsWith(undo.inserted) -> {
                            connection.beginBatchEdit()
                            connection.deleteSurroundingText(undo.inserted.length, 0)
                            connection.commitText(undo.typed + undo.inserted.substring(undo.inserted.length - 1), 1)
                            connection.endBatchEdit()
                            learn(undo.typed, 1)
                        }
                        else -> connection.deleteSurroundingTextInCodePoints(1, 0)
                    }
                }
                "enter" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        },
        onPick = { word, replaces ->
            val connection = currentInputConnection ?: return@KeyboardView
            lastCorrection = null
            swiped = null
            // Choosing the word as typed over the suggestions keeps it on purpose.
            if (word == keyboard?.unknown) learn(word, LEARNED)
            connection.beginBatchEdit()
            connection.deleteSurroundingTextInCodePoints(replaces, 0)
            connection.commitText("$word ", 1)
            connection.endBatchEdit()
        },
    ).also { keyboard = it }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val noSuggestions = info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        suggesting = !password && !noSuggestions
        swiped = null
        // Device colours follow the wallpaper and dark mode, which may have changed.
        keyboard?.theme = Theme.current(this)
        refreshStrip()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshStrip()
    }

    /** Counts a word no dictionary has as kept, when learning is switched on. */
    private fun learn(word: String, times: Int) {
        if (!Dictionaries.preferences(this).getBoolean(Dictionaries.LEARNING, false)) return
        learned[word] = Core.lexiconLearn(lexicon, word, times)
        Dictionaries.saveLearned(this, learned)
    }

    private fun refreshStrip() {
        val view = keyboard ?: return
        val before = currentInputConnection?.getTextBeforeCursor(CONTEXT_LENGTH, 0)
        // A swipe's alternatives stay until the word is changed or left.
        val word = swiped
        if (word != null && before?.endsWith(word) == true) return
        if (!suggesting || before == null) view.clearSuggestions() else view.suggest(lexicon, before.toString())
    }

    /**
     * The band above the keys is for popups only: the app is laid out as if
     * the keyboard started at the strip, and touches on the band reach the app.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = keyboard ?: return
        if (!view.isShown) return
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val contentTop = location[1] + view.headroom
        outInsets.contentTopInsets = contentTop
        outInsets.visibleTopInsets = contentTop
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, contentTop, view.width, location[1] + view.height)
    }

    private companion object {
        /** Enough text before the cursor for the word being typed and the one before it. */
        const val CONTEXT_LENGTH = 64

        /** How often a word has to be kept to count as learned, as the core counts. */
        const val LEARNED = 2

        /** What ends a word and so triggers autocorrect. */
        val SEPARATORS = setOf(" ", ".", ",", "!", "?", ";", ":")

        /** What a swiped word follows without a space. */
        const val OPENING = "([{\"'¿¡"
    }
}
