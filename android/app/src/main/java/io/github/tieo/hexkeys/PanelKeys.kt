package io.github.tieo.hexkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The inset and gap of the panels shown in the keys' place, and the height of their controls, in dp. */
const val PANEL_INSET = 8
const val PANEL_CONTROL = 40

/** A fully rounded shape in `color`, as every control of the panels is. */
fun pill(context: Context, color: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = PANEL_CONTROL * context.resources.displayMetrics.density
}

/**
 * The row at the bottom of every panel in the keys' place: the way back to
 * the letters, space, the long key unnamed as on the keyboard, and a
 * backspace that repeats while held.
 */
@SuppressLint("ViewConstructor")
class PanelKeys(context: Context, onClose: () -> Unit, onSpace: () -> Unit, onBackspace: () -> Unit) : LinearLayout(context) {

    private val backspace = ImageView(context).apply {
        setImageResource(R.drawable.icon_backspace)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    init {
        orientation = HORIZONTAL
        repeatWhileHeld(backspace, onBackspace)
        val gap = (PANEL_INSET * resources.displayMetrics.density).toInt()
        val keys = listOf<Pair<View, Float>>(label("ABC", onClose) to 1f, label("", onSpace) to 3f, backspace to 1f)
        for ((index, entry) in keys.withIndex()) {
            val (view, weight) = entry
            addView(view, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                if (index > 0) marginStart = gap
            })
        }
    }

    fun apply(theme: Theme) {
        backspace.setColorFilter(theme.label)
        for (i in 0 until childCount) {
            val view = getChildAt(i)
            (view as? TextView)?.setTextColor(theme.label)
            view.background = pill(context, theme.other)
        }
    }

    private fun label(text: String, action: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 16f
        gravity = Gravity.CENTER
        setOnClickListener { action() }
    }
}
