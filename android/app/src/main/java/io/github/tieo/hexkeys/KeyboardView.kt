package io.github.tieo.hexkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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

    /** The word being typed when no dictionary has it, which learning counts. */
    var unknown: String? = null
        private set
    /** The slot a finger went down on, or -1 when the touch is not in the strip. */
    private var stripTouch = -1

    /** Where the keys start, below headroom and strip. */
    private val keysTop get() = headroom + stripHeight

    /** Room below the keys for the navigation bar, which the window draws under. */
    private var navigationBar = 0

    private val backgroundPaint = Paint()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17 * resources.displayMetrics.scaledDensity
    }
    private val dividerPaint = Paint().apply {
        strokeWidth = density
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    var theme: Theme = Theme.current(context)
        set(value) {
            field = value
            applyTheme()
            invalidate()
        }

    private fun applyTheme() {
        backgroundPaint.color = theme.background
        // The gap between keys is the background showing through.
        gapPaint.color = theme.background
        popupPaint.color = theme.popup
        selectedPaint.color = theme.accent
        labelPaint.color = theme.label
        stripPaint.color = theme.label
        dividerPaint.color = theme.other
        trailPaint.color = theme.accent
        trailPaint.alpha = 0xB0
    }

    /** A key's colour: its letter's QWERTZ row colour, or the colour of other keys. */
    private fun keyColor(index: Int): Int {
        val row = Core.keyRow(handle, index)
        return if (row in theme.rows.indices) theme.rows[row] else theme.other
    }

    /** Calls the core back when a hold it is timing has lasted long enough. */
    private val tick = Runnable {
        if (handle != 0L) act(Core.tick(handle, SystemClock.uptimeMillis()))
    }

    init {
        applyTheme()
        setOnApplyWindowInsetsListener { _, insets ->
            // Below Android 11 the input method window ends above the navigation bar.
            val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // The navigation bar inset covers only the gesture handle; the
                // back and keyboard switch buttons the system draws for an
                // input method need the taller tappable inset.
                maxOf(
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                    insets.getInsets(WindowInsets.Type.tappableElement()).bottom,
                )
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
        val preferences = Dictionaries.preferences(context)
        val space_middle = preferences.getString(LAYOUT, null) == SPACE_MIDDLE
        handle = Core.create(width.toFloat(), STRETCH, ViewConfiguration.getLongPressTimeout().toLong(), space_middle)
        Core.configure(
            handle,
            preferences.getBoolean(DELETE_FROM_LEFT, false),
            CAPITALS.indexOf(preferences.getString(CAPITAL, null)).coerceAtLeast(0),
        )
        headroom = Core.headroom(handle).toInt()
        loadOutlines()
        setMeasuredDimension(width, keysTop + Core.height(handle).toInt() + navigationBar)
    }

    /**
     * The keys along the delete edge are kept from the system's back
     * gesture, so a slide in from that edge reaches the keyboard. Android
     * honours this for at most 200 dp of an edge's height.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val band = (EXCLUSION_WIDTH * density).toInt()
        val fromLeft = Dictionaries.preferences(context).getBoolean(DELETE_FROM_LEFT, false)
        val x = if (fromLeft) 0 else width - band
        systemGestureExclusionRects = listOf(Rect(x, keysTop, x + band, height - navigationBar))
    }

    /** Shows the strip the core computes for the text before the cursor. */
    fun suggest(lexicon: Long, before: String) {
        if (handle == 0L) return
        val strip = Core.strip(lexicon, handle, before)
        slots = arrayOf(strip[0], strip[1], strip[2])
        replaces = strip[3].toInt()
        correction = strip[4].takeIf { it.isNotEmpty() }?.let { it to replaces }
        unknown = strip[5].takeIf { it.isNotEmpty() }
        invalidate()
    }

    /** The words the last swipe spells, best first. */
    fun swipe(lexicon: Long, before: String): Array<String> =
        if (handle == 0L) emptyArray() else Core.swipe(lexicon, handle, before)

    /** Shows a swipe's words, the one committed in the middle; a choice replaces it. */
    fun showSwipe(words: Array<String>) {
        slots = arrayOf(words.getOrElse(1) { "" }, words[0], words.getOrElse(2) { "" })
        replaces = words[0].codePointCount(0, words[0].length)
        correction = null
        unknown = null
        invalidate()
    }

    fun clearSuggestions() {
        slots = arrayOf("", "", "")
        replaces = 0
        correction = null
        unknown = null
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
            val color = keyColor(index)
            keyPaint.color = if (index == pressed) theme.pressed(color) else color
            canvas.drawPath(path, keyPaint)
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
        val trail = Core.trail(handle)
        if (trail.size >= 4) {
            val path = Path()
            path.moveTo(trail[0], trail[1])
            for (i in 2 until trail.size step 2) path.lineTo(trail[i], trail[i + 1])
            canvas.drawPath(path, trailPaint)
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

    /** The finger the core is following, or -1. */
    private var finger = -1
    private var fingerX = 0f
    private var fingerY = 0f

    /**
     * Touches go to the core, which tells taps, flicks and holds apart; the
     * view only redraws, passes on what they mean, and wakes the core when
     * a hold it is timing is due.
     *
     * The core follows one finger. A second finger coming down ends the
     * first finger's touch where it is, as typing with two thumbs rolls from
     * one key to the next before the first thumb has lifted; the core then
     * follows the new finger and ignores the old one.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handle == 0L) return false
        val index = event.actionIndex
        val time = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (finger >= 0) {
                    lift(fingerX, fingerY, time)
                }
                val x = event.getX(index)
                val y = event.getY(index) - keysTop
                stripTouch = if (y < 0) (x / (width / 3f)).toInt().coerceIn(0, 2) else -1
                if (stripTouch >= 0) {
                    finger = -1
                    return true
                }
                finger = event.getPointerId(index)
                fingerX = x
                fingerY = y
                Core.touchDown(handle, x, y, time)
            }
            MotionEvent.ACTION_MOVE -> {
                val at = event.findPointerIndex(finger)
                if (at < 0) return true
                fingerX = event.getX(at)
                fingerY = event.getY(at) - keysTop
                val action = Core.touchMove(handle, fingerX, fingerY, time)
                if (action[0] != "nothing") act(action)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val x = event.getX(index)
                val y = event.getY(index) - keysTop
                if (stripTouch >= 0 && event.actionMasked == MotionEvent.ACTION_UP) {
                    val slot = (x / (width / 3f)).toInt().coerceIn(0, 2)
                    val word = slots[stripTouch]
                    if (slot == stripTouch && y < 0 && word.isNotEmpty()) onPick(word, replaces)
                    stripTouch = -1
                    return true
                }
                if (event.getPointerId(index) == finger) lift(x, y, time)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                Core.touchCancel(handle)
                finger = -1
                stripTouch = -1
            }
        }
        schedule()
        invalidate()
        return true
    }

    /** Ends the followed finger's touch at a point and acts on it. */
    private fun lift(x: Float, y: Float, time: Long) {
        finger = -1
        act(Core.touchUp(handle, x, y, time))
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

    companion object {
        /** Regular hexagons: at eight to a row they are already finger-sized. */
        private const val STRETCH = 1.0f

        /** Width of the delete edge kept from the back gesture, in dp. */
        private const val EXCLUSION_WIDTH = 32

        /** Preference naming the letter layout; [SPACE_MIDDLE] or anything else for the hexkeys one. */
        const val LAYOUT = "layout"
        const val SPACE_MIDDLE = "space_middle"

        /** Preference: delete swipes come in from the left edge. */
        const val DELETE_FROM_LEFT = "delete_from_left"

        /** Preference naming the capital gesture, one of [CAPITALS] in the core's order. */
        const val CAPITAL = "capital"
        val CAPITALS = listOf("flick_up", "up_and_back", "flick_down", "hold")
    }
}
