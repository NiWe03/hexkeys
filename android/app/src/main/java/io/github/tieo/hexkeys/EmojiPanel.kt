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
    private val onSearch: () -> Unit,
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
    private val searchField = android.widget.ImageView(context).apply {
        setImageResource(R.drawable.icon_search)
        scaleType = android.widget.ImageView.ScaleType.FIT_START
    }
    private val backspace = android.widget.ImageView(context).apply {
        setImageResource(R.drawable.icon_backspace)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
    }
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
        // One inset and one gap throughout, and pills for every control: the
        // search field across the top, as the search field it opens, the tabs,
        // the grid, and the way back, space and backspace at the bottom.
        setPadding(dp(INSET), dp(INSET), dp(INSET), dp(INSET))
        addView(searchField, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CONTROL)))
        searchField.setOnClickListener { onSearch() }
        addView(tabs, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CONTROL)).apply { topMargin = dp(INSET) })
        addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(INSET / 2) })
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CONTROL)).apply { topMargin = dp(INSET / 2) })
        grid.setOnItemClickListener { _, _, position, _ ->
            val emoji = current()[position]
            remember(emoji)
            onEmoji(emoji)
        }
        // Space is the long key, unnamed as on the keyboard.
        val keys = listOf<Pair<View, Float>>(bar("ABC", onClose) to 1f, bar("", onSpace) to 3f, backspace to 1f)
        backspace.setOnClickListener { onBackspace() }
        for ((index, entry) in keys.withIndex()) {
            val (view, weight) = entry
            bottom.addView(view, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                if (index > 0) marginStart = dp(INSET)
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
        searchField.setColorFilter(theme.label)
        searchField.setPadding(dp(14), dp(10), 0, dp(10))
        searchField.background = pill(theme.other)
        backspace.setColorFilter(theme.label)
        for (i in 0 until bottom.childCount) {
            val view = bottom.getChildAt(i)
            (view as? TextView)?.setTextColor(theme.label)
            view.background = pill(theme.other)
        }
        rebuildTabs()
    }

    /** Opens on the recently used, or on the first group when there are none. */
    fun open() {
        shown = if (recent().isEmpty()) 1 else 0
        rebuildTabs()
    }

    private fun current(): List<String> = if (shown == 0) recent() else groups[shown - 1].second

    /** The recently used, most recent first. */
    fun recent(): List<String> =
        Dictionaries.preferences(context).getString(RECENT, "").orEmpty().split(' ').filter { it.isNotEmpty() }

    /** Puts an emoji first among the recently used. */
    fun remember(emoji: String) {
        val list = (listOf(emoji) + recent().filter { it != emoji }).take(RECENT_COUNT)
        Dictionaries.preferences(context).edit().putString(RECENT, list.joinToString(" ")).apply()
    }

    private fun rebuildTabs() {
        tabs.removeAllViews()
        val icons = listOf("") + groups.map { it.second.firstOrNull() ?: "" }
        for ((index, icon) in icons.withIndex()) {
            // Each tab a circle centred in its share of the row, filled while chosen;
            // the recently used have a clock for theirs.
            val mark: View = if (index == 0) {
                android.widget.ImageView(context).apply {
                    setImageResource(R.drawable.icon_schedule)
                    setColorFilter(theme.label)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
            } else {
                TextView(context).apply {
                    text = icon
                    textSize = 20f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
            }
            mark.alpha = if (index == shown) 1f else 0.5f
            mark.background = if (index == shown) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.other)
            } else null
            val cell = android.widget.FrameLayout(context)
            cell.addView(mark, android.widget.FrameLayout.LayoutParams(dp(CONTROL), dp(CONTROL), Gravity.CENTER))
            cell.setOnClickListener {
                shown = index
                rebuildTabs()
            }
            tabs.addView(cell, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        grid.adapter = Cells(current())
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(CONTROL).toFloat()
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

        /** The panel's inset and the gap between its parts, and the height of its controls, in dp. */
        const val INSET = 8
        const val CONTROL = 40
        const val RECENT = "recent_emoji"
        const val RECENT_COUNT = 32
    }
}
