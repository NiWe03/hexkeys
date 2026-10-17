package io.github.tieo.hexkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Emoji in place of the keys: every group in one list that scrolls on, the
 * recently used first, each under its name; above it the search button and
 * an icon per group, the one in view marked, which jumps to its group; at
 * the bottom the way back to the letters, space and backspace. Only emoji
 * the phone's font draws are offered.
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

    /** The groups as name and emoji, in Unicode's order. */
    private val groups: List<Pair<String, List<String>>>

    /** The list's rows: a group's name, or up to [COLUMNS] of its emoji. */
    private sealed interface Row {
        val group: Int
        data class Name(override val group: Int, val name: String) : Row
        data class Emoji(override val group: Int, val emoji: List<String>) : Row
    }
    private var rows: List<Row> = emptyList()

    /** Where each group's name row is, the recently used being group 0. */
    private var starts: List<Int> = emptyList()

    /** The group marked in the bar: the one at the top of the list. */
    private var marked = 0

    private val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val list = ListView(context).apply {
        divider = null
        dividerHeight = 0
        isVerticalScrollBarEnabled = false
        selector = GradientDrawable()
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
        val paint = Paint()
        groups = context.assets.open("emoji.tsv").bufferedReader().readLines().mapNotNull { line ->
            val (name, emoji) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            name to emoji.split(' ').filter { paint.hasGlyph(it) }
        }
        // One inset and one gap throughout, circles and pills for the controls.
        setPadding(dp(INSET), dp(INSET), dp(INSET), dp(INSET))
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CONTROL)))
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(INSET / 2) })
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(CONTROL)).apply { topMargin = dp(INSET / 2) })
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                val group = rows.getOrNull(first)?.group ?: return
                if (group != marked) {
                    marked = group
                    markBar()
                }
            }
        })
        // Space is the long key, unnamed as on the keyboard.
        val keys = listOf<Pair<View, Float>>(label("ABC", onClose) to 1f, label("", onSpace) to 3f, backspace to 1f)
        backspace.setOnClickListener { onBackspace() }
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
        for (i in 0 until bottom.childCount) {
            val view = bottom.getChildAt(i)
            (view as? TextView)?.setTextColor(theme.label)
            view.background = pill(theme.other)
        }
        buildBar()
        open()
    }

    /** Opens at the top: the recently used, or the first group when there are none. */
    fun open() {
        buildRows()
        list.setSelection(0)
        marked = rows.firstOrNull()?.group ?: 0
        markBar()
    }

    /** The recently used, most recent first. */
    fun recent(): List<String> =
        Dictionaries.preferences(context).getString(RECENT, "").orEmpty().split(' ').filter { it.isNotEmpty() }

    /** Puts an emoji first among the recently used. */
    fun remember(emoji: String) {
        val list = (listOf(emoji) + recent().filter { it != emoji }).take(RECENT_COUNT)
        Dictionaries.preferences(context).edit().putString(RECENT, list.joinToString(" ")).apply()
    }

    private fun buildRows() {
        val all = listOf("Recently used" to recent()) + groups
        val built = mutableListOf<Row>()
        val at = mutableListOf<Int>()
        for ((group, entry) in all.withIndex()) {
            val (name, emoji) = entry
            at += built.size
            if (emoji.isEmpty()) continue
            built += Row.Name(group, name)
            emoji.chunked(COLUMNS).forEach { built += Row.Emoji(group, it) }
        }
        rows = built
        starts = at
        list.adapter = Rows()
    }

    /** The search button, then a button per group with its icon. */
    private fun buildBar() {
        bar.removeAllViews()
        val search = ImageView(context).apply {
            setImageResource(R.drawable.icon_search)
            setColorFilter(theme.label)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = pill(theme.other)
            setOnClickListener { onSearch() }
        }
        bar.addView(search, LayoutParams(dp(CONTROL * 2), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(INSET / 2) })
        for ((group, icon) in GROUP_ICONS.withIndex()) {
            val mark = ImageView(context).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(9), dp(9), dp(9), dp(9))
            }
            val cell = FrameLayout(context)
            cell.addView(mark, FrameLayout.LayoutParams(dp(CONTROL - 4), dp(CONTROL - 4), Gravity.CENTER))
            cell.setOnClickListener {
                // A group with no emoji, the recently used before any, has none to show.
                val start = starts.getOrNull(group) ?: return@setOnClickListener
                if (start < rows.size && rows[start].group == group) list.setSelection(start)
            }
            bar.addView(cell, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        markBar()
    }

    /** The group in view in a filled circle, the others plain and dimmer. */
    private fun markBar() {
        for (group in GROUP_ICONS.indices) {
            val cell = bar.getChildAt(group + 1) as? FrameLayout ?: continue
            val mark = cell.getChildAt(0) as ImageView
            val chosen = group == marked
            mark.setColorFilter(theme.label)
            mark.alpha = if (chosen) 1f else 0.55f
            mark.background = if (chosen) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.other)
            } else null
        }
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

    private inner class Rows : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Name) 0 else 1
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = when (val row = rows[position]) {
            is Row.Name -> (convertView as? TextView ?: TextView(context).apply {
                textSize = 13f
                setPadding(dp(INSET / 2), dp(10), 0, dp(4))
            }).apply {
                text = row.name
                setTextColor(theme.label)
                alpha = 0.6f
            }
            is Row.Emoji -> (convertView as? LinearLayout ?: LinearLayout(context).apply {
                orientation = HORIZONTAL
                repeat(COLUMNS) {
                    addView(TextView(context).apply {
                        textSize = 26f
                        gravity = Gravity.CENTER
                        minHeight = dp(48)
                    }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }).apply {
                for (i in 0 until COLUMNS) {
                    val cell = getChildAt(i) as TextView
                    val emoji = row.emoji.getOrNull(i)
                    cell.text = emoji.orEmpty()
                    cell.setOnClickListener(emoji?.let { e ->
                        View.OnClickListener {
                            remember(e)
                            onEmoji(e)
                        }
                    })
                    cell.isClickable = emoji != null
                }
            }
        }
    }

    private companion object {
        const val COLUMNS = 8
        const val RECENT = "recent_emoji"
        const val RECENT_COUNT = 32

        /** The panel's inset and the gap between its parts, and the height of its controls, in dp. */
        const val INSET = 8
        const val CONTROL = 40

        /** An icon for the recently used and for each group of emoji.tsv, in its order. */
        val GROUP_ICONS = listOf(
            R.drawable.icon_schedule, R.drawable.icon_mood, R.drawable.icon_emoji_people, R.drawable.icon_pets,
            R.drawable.icon_restaurant, R.drawable.icon_directions_car, R.drawable.icon_sports_soccer,
            R.drawable.icon_lightbulb, R.drawable.icon_emoji_symbols, R.drawable.icon_flag,
        )
    }
}
