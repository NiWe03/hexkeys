package io.github.tieo.hexkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Emoji in place of the keys: a tab per Unicode group with the recently
 * used first, a grid of the group's emoji in Unicode's order, and at the
 * bottom the way back to the letters, space and backspace. Only emoji the
 * phone's font draws are offered.
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onSpace: () -> Unit,
    private val onBackspace: () -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    /** The groups as name and emoji, the recently used first. */
    private val groups: List<Pair<String, List<String>>>
    private var shown = 0
    private val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val grid = GridView(context).apply {
        numColumns = COLUMNS
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        isVerticalScrollBarEnabled = false
        selector = GradientDrawable()
    }
    private val bottom = LinearLayout(context).apply { orientation = HORIZONTAL }
    private var theme = Theme.current(context)

    init {
        orientation = VERTICAL
        // Touches between the panel's controls must not reach the keys underneath.
        isClickable = true
        val paint = Paint()
        groups = context.assets.open("emoji.tsv").bufferedReader().readLines().mapNotNull { line ->
            val (name, emoji) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            name to emoji.split(' ').filter { paint.hasGlyph(it) }
        }
        addView(tabs, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        grid.setOnItemClickListener { _, _, position, _ ->
            val emoji = current()[position]
            remember(emoji)
            onEmoji(emoji)
        }
        for ((label, weight, action) in listOf(
            Triple("ABC", 1f, onClose),
            Triple("space", 3f, onSpace),
            Triple("", 1f, onBackspace),
        )) {
            bottom.addView(bar(label, action), LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                setMargins(dp(3), dp(4), dp(3), dp(4))
            })
        }
        shown = if (recent().isEmpty()) 1 else 0
        rebuildTabs()
        apply(theme)
    }

    /** Follows the keyboard's theme. */
    fun apply(theme: Theme) {
        this.theme = theme
        setBackgroundColor(theme.background)
        for (i in 0 until bottom.childCount) {
            val view = bottom.getChildAt(i) as TextView
            view.setTextColor(theme.label)
            if (i == bottom.childCount - 1) {
                val backspace = context.getDrawable(R.drawable.icon_backspace)!!.mutate()
                backspace.setTint(theme.label)
                backspace.setBounds(0, 0, dp(24), dp(24))
                view.setCompoundDrawables(backspace, null, null, null)
                view.setPadding(dp(24), 0, 0, 0)
            }
            view.background = GradientDrawable().apply {
                setColor(theme.other)
                cornerRadius = dp(10).toFloat()
            }
        }
        rebuildTabs()
    }

    /** Opens on the recently used, or on the first group when there are none. */
    fun open() {
        shown = if (recent().isEmpty()) 1 else 0
        rebuildTabs()
    }

    private fun current(): List<String> = if (shown == 0) recent() else groups[shown - 1].second

    private fun recent(): List<String> =
        Dictionaries.preferences(context).getString(RECENT, "").orEmpty().split(' ').filter { it.isNotEmpty() }

    private fun remember(emoji: String) {
        val list = (listOf(emoji) + recent().filter { it != emoji }).take(RECENT_COUNT)
        Dictionaries.preferences(context).edit().putString(RECENT, list.joinToString(" ")).apply()
    }

    private fun rebuildTabs() {
        tabs.removeAllViews()
        val icons = listOf("") + groups.map { it.second.firstOrNull() ?: "" }
        for ((index, icon) in icons.withIndex()) {
            tabs.addView(TextView(context).apply {
                text = icon
                textSize = 20f
                gravity = Gravity.CENTER
                // The recently used have a clock for their tab.
                if (index == 0) {
                    val clock = context.getDrawable(R.drawable.icon_schedule)!!.mutate()
                    clock.setTint(theme.label)
                    clock.setBounds(0, 0, dp(24), dp(24))
                    setCompoundDrawables(clock, null, null, null)
                    setPadding(dp(8), 0, 0, 0)
                }
                alpha = if (index == shown) 1f else 0.45f
                background = if (index == shown) GradientDrawable().apply {
                    setColor(theme.other)
                    cornerRadius = dp(10).toFloat()
                } else null
                setOnClickListener {
                    shown = index
                    rebuildTabs()
                }
            }, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        grid.adapter = Cells(current())
    }

    private fun bar(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setOnClickListener { action() }
    }

    private inner class Cells(private val emoji: List<String>) : BaseAdapter() {
        override fun getCount() = emoji.size
        override fun getItem(position: Int) = emoji[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView as? TextView ?: TextView(context).apply {
                textSize = 26f
                gravity = Gravity.CENTER
                minHeight = dp(52)
            }
            view.text = emoji[position]
            return view
        }
    }

    private companion object {
        const val COLUMNS = 8
        const val RECENT = "recent_emoji"
        const val RECENT_COUNT = 32
    }
}
