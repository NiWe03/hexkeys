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
class KeyboardFrame(context: Context, val keyboard: KeyboardView) : FrameLayout(context) {

    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(chips)
    }
    private val pinned = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val gap = (4 * resources.displayMetrics.density).toInt()

    init {
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> clipChips() }
        addView(keyboard)
        addView(scroller)
        addView(pinned)
        show(emptyList())
    }

    /** Shows autofill suggestion views, each with whether the service pinned it; none hides them. */
    fun show(suggestions: List<Pair<View, Boolean>>) {
        chips.removeAllViews()
        pinned.removeAllViews()
        for ((view, isPinned) in suggestions) {
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        keyboard.measure(widthMeasureSpec, heightMeasureSpec)
        val width = keyboard.measuredWidth
        val strip = MeasureSpec.makeMeasureSpec(keyboard.stripTall - 2 * gap, MeasureSpec.EXACTLY)
        pinned.measure(MeasureSpec.makeMeasureSpec(width / 3, MeasureSpec.AT_MOST), strip)
        val pinnedWidth = maxOf(pinned.measuredWidth, keyboard.stripSide)
        scroller.measure(MeasureSpec.makeMeasureSpec(width - keyboard.stripSide - pinnedWidth, MeasureSpec.EXACTLY), strip)
        setMeasuredDimension(width, keyboard.measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
        val stripTop = keyboard.headroom + gap
        val width = keyboard.measuredWidth
        pinned.layout(width - pinned.measuredWidth, stripTop, width, stripTop + pinned.measuredHeight)
        val start = keyboard.stripSide
        scroller.layout(start, stripTop, start + scroller.measuredWidth, stripTop + scroller.measuredHeight)
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
