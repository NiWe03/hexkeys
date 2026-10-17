package io.github.tieo.hexkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets

/**
 * Draws the keys the core lays out and passes touches back to it.
 *
 * Outlines come from the core and tile the keyboard; each is drawn with a
 * stroke in the background colour, which reads as the gap between keys
 * without changing where a touch lands.
 *
 * Above the keys runs the suggestion strip: three words, the best in the
 * middle; tapping one hands it to [onPick] with how many characters before
 * the cursor it replaces.
 *
 * The view is taller than strip and keys by the core's headroom, a
 * transparent band on top where a long-press popup over the top row can
 * appear. The input method reports only strip and keys as its content and
 * touchable area, so the band neither pushes the app up nor takes its touches.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val onAction: (kind: String, text: String) -> Unit,
    private val onPick: (word: String, replaces: Int) -> Unit,
) : View(context) {

    private var handle = 0L
    private var paths: List<Path> = emptyList()

    /** Height of the transparent band above the strip. */
    var headroom = 0
        private set

    private val density = resources.displayMetrics.density
    private val stripHeight = (44 * density).toInt()
    private var slots = arrayOf("", "", "")
    private var replaces = 0

    /** What autocorrect would put in place of the word being typed, with how many code points it replaces. */
    var correction: Pair<String, Int>? = null
        private set
    /** The slot a finger went down on, or -1 when the touch is not in the strip. */
    private var stripTouch = -1

    /** Where the keys start, below headroom and strip. */
    private val keysTop get() = headroom + stripHeight

    /** Room below the keys for the navigation bar, which the window draws under. */
    private var navigationBar = 0

    private val background = 0xFF1B1B1F.toInt()
    private val backgroundPaint = Paint().apply { color = background }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A42.toInt() }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF55555F.toInt() }
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4A4A54.toInt() }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6C7BD8.toInt() }
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = background
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E8EE.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E8EE.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 17 * resources.displayMetrics.scaledDensity
    }
    private val dividerPaint = Paint().apply {
        color = 0xFF3A3A42.toInt()
        strokeWidth = density
    }

    /** Calls the core back when a hold it is timing has lasted long enough. */
    private val tick = Runnable {
        if (handle != 0L) act(Core.tick(handle, SystemClock.uptimeMillis()))
    }

    init {
        setOnApplyWindowInsetsListener { _, insets ->
            // Below Android 11 the input method window ends above the navigation bar.
            val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                0
            }
            if (bottom != navigationBar) {
                navigationBar = bottom
                requestLayout()
            }
            insets
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (handle != 0L) Core.destroy(handle)
        handle = Core.create(width.toFloat(), STRETCH, ViewConfiguration.getLongPressTimeout().toLong())
        headroom = Core.headroom(handle).toInt()
        loadOutlines()
        setMeasuredDimension(width, keysTop + Core.height(handle).toInt() + navigationBar)
    }

    /** Shows the strip the core computes for the text before the cursor. */
    fun suggest(lexicon: Long, before: String) {
        if (handle == 0L) return
        val strip = Core.strip(lexicon, handle, before)
        slots = arrayOf(strip[0], strip[1], strip[2])
        replaces = strip[3].toInt()
        correction = strip[4].takeIf { it.isNotEmpty() }?.let { it to replaces }
        invalidate()
    }

    fun clearSuggestions() {
        slots = arrayOf("", "", "")
        replaces = 0
        correction = null
        invalidate()
    }

    /** Every layer tiles the same area, so a layer change only swaps the outlines. */
    private fun loadOutlines() {
        paths = (0 until Core.keyCount(handle)).map { path(Core.outline(handle, it)) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
        if (handle != 0L) Core.destroy(handle)
        handle = 0L
    }

    override fun onDraw(canvas: Canvas) {
        if (handle == 0L) return
        canvas.drawRect(0f, headroom.toFloat(), width.toFloat(), height.toFloat(), backgroundPaint)
        drawStrip(canvas)
        canvas.save()
        canvas.translate(0f, keysTop.toFloat())
        val cellWidth = Core.cellWidth(handle)
        val pressed = Core.pressed(handle)
        for ((index, path) in paths.withIndex()) {
            canvas.drawPath(path, if (index == pressed) pressedPaint else keyPaint)
            canvas.drawPath(path, gapPaint)
            drawLabel(canvas, Core.label(handle, index), Core.labelPosition(handle, index), cellWidth)
        }
        val choices = Core.popupCount(handle)
        if (choices > 0) {
            val selected = Core.popupSelected(handle)
            for (index in 0 until choices) {
                val path = path(Core.popupOutline(handle, index))
                canvas.drawPath(path, if (index == selected) selectedPaint else popupPaint)
                canvas.drawPath(path, gapPaint)
                drawLabel(canvas, Core.popupLabel(handle, index), Core.popupLabelPosition(handle, index), cellWidth)
            }
        }
        canvas.restore()
    }

    /** Three equal slots, the middle one bold; long words shrink to fit their slot. */
    private fun drawStrip(canvas: Canvas) {
        val slotWidth = width / 3f
        val middle = headroom + stripHeight / 2f
        for ((index, word) in slots.withIndex()) {
            if (word.isEmpty()) continue
            stripPaint.isFakeBoldText = index == 1
            stripPaint.textSize = 17 * resources.displayMetrics.scaledDensity
            val room = slotWidth - 12 * density
            val measured = stripPaint.measureText(word)
            if (measured > room) stripPaint.textSize *= room / measured
            val baseline = middle - (stripPaint.descent() + stripPaint.ascent()) / 2
            canvas.drawText(word, slotWidth * (index + 0.5f), baseline, stripPaint)
        }
        for (index in 1..2) {
            val x = slotWidth * index
            canvas.drawLine(x, headroom + stripHeight * 0.3f, x, headroom + stripHeight * 0.7f, dividerPaint)
        }
    }

    /** Single characters large, words such as "space" smaller so they fit their key. */
    private fun drawLabel(canvas: Canvas, label: String, position: FloatArray, cellWidth: Float) {
        labelPaint.textSize = cellWidth * if (label.codePointCount(0, label.length) == 1) 0.36f else 0.24f
        val baseline = position[1] - (labelPaint.descent() + labelPaint.ascent()) / 2
        canvas.drawText(label, position[0], baseline, labelPaint)
    }

    private fun path(corners: FloatArray) = Path().apply {
        moveTo(corners[0], corners[1])
        for (i in 2 until corners.size step 2) lineTo(corners[i], corners[i + 1])
        close()
    }

    /**
     * Touches go to the core, which tells taps, flicks and holds apart; the
     * view only redraws, passes on what they mean, and wakes the core when
     * a hold it is timing is due.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handle == 0L) return false
        val x = event.x
        val y = event.y - keysTop
        val time = event.eventTime
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stripTouch = if (y < 0) (x / (width / 3f)).toInt().coerceIn(0, 2) else -1
        }
        if (stripTouch >= 0) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val slot = (x / (width / 3f)).toInt().coerceIn(0, 2)
                val word = slots[stripTouch]
                if (slot == stripTouch && y < 0 && word.isNotEmpty()) onPick(word, replaces)
                stripTouch = -1
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> Core.touchDown(handle, x, y, time)
            MotionEvent.ACTION_MOVE -> Core.touchMove(handle, x, y, time)
            MotionEvent.ACTION_UP -> {
                act(Core.touchUp(handle, x, y, time))
                return true
            }
            MotionEvent.ACTION_CANCEL -> Core.touchCancel(handle)
        }
        schedule()
        invalidate()
        return true
    }

    private fun act(action: Array<String>) {
        val (kind, text) = action
        if (kind == "redraw") loadOutlines()
        onAction(kind, text)
        schedule()
        invalidate()
    }

    private fun schedule() {
        removeCallbacks(tick)
        val due = Core.deadline(handle)
        if (due >= 0) postDelayed(tick, (due - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private companion object {
        /** Regular hexagons: at eight to a row they are already finger-sized. */
        const val STRETCH = 1.0f
    }
}
