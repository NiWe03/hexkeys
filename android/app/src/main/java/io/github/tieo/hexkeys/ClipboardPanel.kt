package io.github.tieo.hexkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * The texts copied, in place of the keys, newest first and the pinned
 * marked: tapping one puts it in, its pin keeps it past the hour, its cross
 * removes it. At the bottom, as on the emoji, the way back to the letters,
 * space and backspace; with nothing copied a faint clipboard fills the room.
 */
@SuppressLint("ViewConstructor")
class ClipboardPanel(
    context: Context,
    private val clips: Clips,
    private val onPaste: (String) -> Unit,
    private val onSpace: () -> Unit,
    private val onBackspace: () -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    private var shown: List<Clip> = emptyList()
    private val list = ListView(context).apply {
        divider = null
        dividerHeight = 0
        isVerticalScrollBarEnabled = false
        selector = GradientDrawable()
    }
    private val empty = ImageView(context).apply {
        setImageResource(R.drawable.icon_content_paste)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        alpha = 0.25f
    }
    private val bottom = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val backspace = ImageView(context).apply {
        setImageResource(R.drawable.icon_backspace)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }
    private var theme = Theme.current(context)

    init {
        orientation = VERTICAL
        // Touches between the panel's controls must not reach the keys underneath.
        isClickable = true
        setPadding(dp(INSET), dp(INSET), dp(INSET), dp(INSET))
        val room = FrameLayout(context)
        room.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        room.addView(empty, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        addView(room, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CONTROL)).apply { topMargin = dp(INSET) })
        repeatWhileHeld(backspace, onBackspace)
        val keys = listOf<Pair<View, Float>>(label("ABC", onClose) to 1f, label("", onSpace) to 3f, backspace to 1f)
        for ((index, entry) in keys.withIndex()) {
            val (view, weight) = entry
            bottom.addView(view, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                if (index > 0) marginStart = dp(INSET)
            })
        }
        apply(theme)
    }

    /** Follows the keyboard's theme. */
    fun apply(theme: Theme) {
        this.theme = theme
        setBackgroundColor(theme.background)
        backspace.setColorFilter(theme.label)
        empty.setColorFilter(theme.label)
        for (i in 0 until bottom.childCount) {
            val view = bottom.getChildAt(i)
            (view as? TextView)?.setTextColor(theme.label)
            view.background = pill(theme.other)
        }
        open()
    }

    /** Shows the clips as they are now. */
    fun open() {
        shown = clips.all()
        empty.visibility = if (shown.isEmpty()) VISIBLE else GONE
        list.adapter = Rows()
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(CONTROL).toFloat()
    }

    private fun label(text: String, action: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 16f
        gravity = Gravity.CENTER
        setOnClickListener { action() }
    }

    private fun icon(id: Int) = ImageView(context).apply {
        setImageResource(id)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val clip = shown[position]
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(theme.other)
                    cornerRadius = dp(CONTROL / 2).toFloat()
                }
                setPadding(dp(16), dp(4), dp(4), dp(4))
                setOnClickListener { onPaste(clip.text) }
            }
            row.addView(TextView(context).apply {
                text = clip.text.replace('\n', ' ')
                textSize = 15f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(theme.label)
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // The pin lit while pinned, dim while the clip goes after the hour.
            row.addView(icon(R.drawable.icon_push_pin).apply {
                setColorFilter(if (clip.pinned) theme.accent else theme.label)
                alpha = if (clip.pinned) 1f else 0.5f
                setOnClickListener {
                    clips.pin(clip, !clip.pinned)
                    open()
                }
            }, LayoutParams(dp(CONTROL), dp(CONTROL)))
            row.addView(icon(R.drawable.icon_close).apply {
                setColorFilter(theme.label)
                alpha = 0.5f
                setOnClickListener {
                    clips.remove(clip)
                    open()
                }
            }, LayoutParams(dp(CONTROL), dp(CONTROL)))
            row.minimumHeight = dp(CONTROL + 8)
            return FrameLayout(context).apply {
                setPadding(0, 0, 0, dp(INSET / 2))
                addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    private companion object {
        const val INSET = 8
        const val CONTROL = 40
    }
}
