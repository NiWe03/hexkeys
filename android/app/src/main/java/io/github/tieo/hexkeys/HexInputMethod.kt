package io.github.tieo.hexkeys

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View

/** The input method: hands the keyboard view the app it types into. */
class HexInputMethod : InputMethodService() {

    override fun onCreateInputView(): View = KeyboardView(this) { kind, text ->
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
    }
}
