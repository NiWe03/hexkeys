package io.github.tieo.hexkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * The keyboard view with the autofill service's suggestions laid over its
 * strip. The service's own button, which it marks pinned, sits in the
 * strip's right end slot and never scrolls away; the suggestions that fill
 * a field, accounts and passkeys, line up left of it and scroll when there
 * are more than fit. While any are shown they take the place of the words.
 */
@SuppressLint("ViewConstructor")
class KeyboardFrame(context: Context, val keyboard: KeyboardView, val emoji: EmojiPanel, val clipboard: ClipboardPanel) : FrameLayout(context) {

    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(chips)
    }
    private val pinned = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val gap = (4 * resources.displayMetrics.density).toInt()
    private val density = resources.displayMetrics.density

    /**
     * Searching emoji by name while the letters type the query: over the
     * strip a way back to the emoji and the query in a search field with
     * its cursor, and over the number row the emoji it finds, the recently
     * used until something is typed.
     */
    private val search = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    private val searchBack = android.widget.ImageView(context).apply {
        setImageResource(R.drawable.icon_arrow_back)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
    }
    private val query = android.widget.TextView(context).apply {
        textSize = 17f
        maxLines = 1
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        compoundDrawablePadding = (8 * density).toInt()
    }
    private val results = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    private val resultScroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(results)
        // Touches between the emoji must not reach the number keys underneath.
        isClickable = true
    }
    private var cursorShown = true
    private var queryText = ""
    private val blink = object : Runnable {
        override fun run() {
            cursorShown = !cursorShown
            paintQuery()
            postDelayed(this, 530)
        }
    }

    init {
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> clipChips() }
        addView(keyboard)
        addView(scroller)
        addView(pinned)
        addView(emoji)
        emoji.visibility = GONE
        addView(clipboard)
        clipboard.visibility = GONE
        search.addView(searchBack, LinearLayout.LayoutParams(keyboard.stripSide, ViewGroup.LayoutParams.MATCH_PARENT))
        search.addView(query, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(0, gap, gap * 2, gap)
        })
        addView(search)
        addView(resultScroller)
        search.visibility = GONE
        resultScroller.visibility = GONE
        show(emptyList())
    }

    val searching get() = search.visibility == VISIBLE

    /** Shows or hides the emoji search; `back` returns to the emoji. */
    fun showSearch(on: Boolean, back: () -> Unit = {}) {
        search.visibility = if (on) VISIBLE else GONE
        resultScroller.visibility = search.visibility
        removeCallbacks(blink)
        if (on) {
            val theme = keyboard.theme
            search.setBackgroundColor(theme.background)
            resultScroller.setBackgroundColor(theme.background)
            searchBack.setColorFilter(theme.label)
            searchBack.setOnClickListener { back() }
            query.setTextColor(theme.label)
            query.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(theme.other)
                cornerRadius = 100 * density
            }
            val magnifier = context.getDrawable(R.drawable.icon_search)!!.mutate()
            magnifier.setTint(theme.label)
            val size = (20 * density).toInt()
            magnifier.setBounds(0, 0, size, size)
            query.setCompoundDrawables(magnifier, null, null, null)
            showEmoji(false)
            post(blink)
        }
        requestLayout()
    }

    /** The query typed so far and the emoji it finds; tapping one picks it. */
    fun showResults(text: String, found: List<String>, pick: (String) -> Unit) {
        queryText = text
        cursorShown = true
        paintQuery()
        results.removeAllViews()
        for (emoji in found) {
            results.addView(android.widget.TextView(context).apply {
                this.text = emoji
                textSize = 28f
                gravity = android.view.Gravity.CENTER
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                setOnClickListener { pick(emoji) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        resultScroller.scrollTo(0, 0)
    }

    /** The query with a cursor after it in the accent colour, blinking. */
    private fun paintQuery() {
        val text = android.text.SpannableStringBuilder(queryText).append("\u2009|")
        val color = if (cursorShown) keyboard.theme.accent else 0
        text.setSpan(android.text.style.ForegroundColorSpan(color), text.length - 1, text.length, 0)
        query.text = text
    }

    /**
     * Shows autofill suggestion views, each with whether the service pinned
     * it; none hides them. Where the service pins none, its last suggestion
     * is taken for its button: services add theirs last (Bitwarden does,
     * unpinned), so it is the one that has to stay in reach.
     */
    fun show(suggestions: List<Pair<View, Boolean>>) {
        chips.removeAllViews()
        pinned.removeAllViews()
        val anyPinned = suggestions.any { it.second }
        for ((index, entry) in suggestions.withIndex()) {
            val (view, servicePinned) = entry
            val isPinned = servicePinned || (!anyPinned && index == suggestions.lastIndex)
            // The view comes sized to what the service rendered; keep that.
            val given = view.layoutParams
            val params = LinearLayout.LayoutParams(
                given?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                given?.height ?: ViewGroup.LayoutParams.MATCH_PARENT,
            )
            params.gravity = android.view.Gravity.CENTER_VERTICAL
            params.marginEnd = gap
            (if (isPinned) pinned else chips).addView(view, params)
        }
        val any = suggestions.isNotEmpty()
        scroller.visibility = if (chips.childCount > 0) VISIBLE else GONE
        pinned.visibility = if (pinned.childCount > 0) VISIBLE else GONE
        keyboard.chipsShown = any
        requestLayout()
    }

    /** Shows the emoji in place of the keys, or the keys again. */
    fun showEmoji(on: Boolean) {
        if (on) {
            emoji.apply(keyboard.theme)
            emoji.open()
            clipboard.visibility = GONE
        }
        emoji.visibility = if (on) VISIBLE else GONE
        requestLayout()
    }

    val emojiShown get() = emoji.visibility == VISIBLE

    /** Shows the texts copied in place of the keys, or the keys again. */
    fun showClipboard(on: Boolean) {
        if (on) {
            clipboard.apply(keyboard.theme)
            emoji.visibility = GONE
        }
        clipboard.visibility = if (on) VISIBLE else GONE
        requestLayout()
    }

    val clipboardShown get() = clipboard.visibility == VISIBLE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        keyboard.measure(widthMeasureSpec, heightMeasureSpec)
        val keysTop = keyboard.headroom + keyboard.stripTall
        for (panel in listOf(emoji, clipboard)) {
            panel.measure(
                MeasureSpec.makeMeasureSpec(keyboard.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(keyboard.measuredHeight - keysTop - keyboard.navigationBar, MeasureSpec.EXACTLY),
            )
        }
        val width = keyboard.measuredWidth
        val strip = MeasureSpec.makeMeasureSpec(keyboard.stripTall - 2 * gap, MeasureSpec.EXACTLY)
        pinned.measure(MeasureSpec.makeMeasureSpec(width / 3, MeasureSpec.AT_MOST), strip)
        val pinnedWidth = maxOf(pinned.measuredWidth, keyboard.stripSide)
        scroller.measure(MeasureSpec.makeMeasureSpec(width - 2 * keyboard.stripSide - pinnedWidth, MeasureSpec.EXACTLY), strip)
        search.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(keyboard.stripTall, MeasureSpec.EXACTLY),
        )
        resultScroller.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(keyboard.numbersHeight, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, keyboard.measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
        val stripTop = keyboard.headroom + gap
        val width = keyboard.measuredWidth
        pinned.layout(width - pinned.measuredWidth, stripTop, width, stripTop + pinned.measuredHeight)
        val start = 2 * keyboard.stripSide
        val keysTop = keyboard.headroom + keyboard.stripTall
        emoji.layout(0, keysTop, width, keysTop + emoji.measuredHeight)
        clipboard.layout(0, keysTop, width, keysTop + clipboard.measuredHeight)
        scroller.layout(start, stripTop, start + scroller.measuredWidth, stripTop + scroller.measuredHeight)
        search.layout(0, keyboard.headroom, width, keyboard.headroom + search.measuredHeight)
        resultScroller.layout(0, keysTop, width, keysTop + resultScroller.measuredHeight)
        post { clipChips() }
    }

    /**
     * Autofill suggestions are drawn on surfaces of their own, which the
     * scroller does not clip; each is cut to the part inside it by hand.
     */
    private fun clipChips() {
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i)
            val left = chip.left - scroller.scrollX
            val from = maxOf(0, -left)
            val to = minOf(chip.width, scroller.width - left)
            chip.clipBounds = if (to > from) Rect(from, 0, to, chip.height) else Rect()
        }
    }
}
