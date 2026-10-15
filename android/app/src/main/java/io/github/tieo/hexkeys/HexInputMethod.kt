package io.github.tieo.hexkeys

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View

/** The input method: hands the keyboard view the app it types into. */
class HexInputMethod : InputMethodService() {

    private var keyboard: KeyboardView? = null

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
    }.also { keyboard = it }

    /**
     * The band above the keys is for popups only: the app is laid out as if
     * the keyboard started at the keys, and touches on the band reach the app.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = keyboard ?: return
        if (!view.isShown) return
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val keysTop = location[1] + view.headroom
        outInsets.contentTopInsets = keysTop
        outInsets.visibleTopInsets = keysTop
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, keysTop, view.width, location[1] + view.height)
    }
}
