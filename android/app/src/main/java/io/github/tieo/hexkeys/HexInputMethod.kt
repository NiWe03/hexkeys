package io.github.tieo.hexkeys

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

    override fun onCreate() {
        super.onCreate()
        lexicon = Core.lexiconCreate()
        assets.open(DEFAULT_DICTIONARY).use { Core.lexiconAdd(lexicon, it.readBytes()) }
    }

    override fun onDestroy() {
        super.onDestroy()
        Core.lexiconDestroy(lexicon)
        lexicon = 0L
    }

    override fun onCreateInputView(): View = KeyboardView(
        this,
        onAction = { kind, text ->
            val connection = currentInputConnection ?: return@KeyboardView
            when (kind) {
                "commit" -> connection.commitText(text, 1)
                "backspace" -> {
                    val selected = connection.getSelectedText(0)
                    if (selected.isNullOrEmpty()) connection.deleteSurroundingTextInCodePoints(1, 0)
                    else connection.commitText("", 1)
                }
                "enter" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        },
        onPick = { word, replaces ->
            val connection = currentInputConnection ?: return@KeyboardView
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
        refreshStrip()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshStrip()
    }

    private fun refreshStrip() {
        val view = keyboard ?: return
        val before = currentInputConnection?.getTextBeforeCursor(CONTEXT_LENGTH, 0)
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
        /** English (US) from the AOSP dictionaries, built in so the keyboard suggests from the start. */
        const val DEFAULT_DICTIONARY = "main_en_us.dict"

        /** Enough text before the cursor for the word being typed and the one before it. */
        const val CONTEXT_LENGTH = 64
    }
}
