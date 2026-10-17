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
import org.json.JSONArray
import org.json.JSONObject

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
    private val onSettings: () -> Unit,
) : View(context) {

    private var handle = 0L
    private var paths: List<Path> = emptyList()

    /** Height of the transparent band above the strip. */
    var headroom = 0
        private set

    private val density = resources.displayMetrics.density
    private val stripHeight = (STRIP_DP * density).toInt()
    private var slots = arrayOf("", "", "")
    private var replaces = 0

    /** What autocorrect would put in place of the word being typed, with how many code points it replaces. */
    var correction: Pair<String, Int>? = null
        private set

    /** The word being typed when no dictionary has it, which learning counts. */
    var unknown: String? = null
        private set
    /** Width of the strip's end slots: the settings button left, the autofill service's button right. */
    val stripSide get() = stripHeight + (4 * density).toInt()

    /** Height of the suggestion strip. */
    val stripTall get() = stripHeight

    /** What holding a letter offers, as the core's `lexiconAccents` gives it. */
    var accents = ""
        set(value) {
            field = value
            if (handle != 0L) Core.setAccents(handle, value)
        }

    /** Autofill chips cover the words, which then are not drawn. */
    var chipsShown = false
        set(value) {
            field = value
            invalidate()
        }

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
    /** Whether flicking down rather than up types a key's alternative, which then shows below its sign. */
    private var hintBelow = false

    /** The small sign on a punctuation key for what flicking it types. */
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        hintPaint.color = theme.label
        hintPaint.alpha = 0xA8
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
        Core.setAccents(handle, accents)
        Core.configure(
            handle,
            preferences.getBoolean(DELETE_FROM_LEFT, false),
            CAPITALS.indexOf(preferences.getString(CAPITAL, null)).coerceAtLeast(0),
        )
        hintBelow = preferences.getString(CAPITAL, null) == "flick_down"
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
            val position = Core.labelPosition(handle, index)
            val hint = Core.keyHint(handle, index)
            if (hint.isEmpty()) {
                drawLabel(canvas, Core.label(handle, index), position, cellWidth)
            } else {
                // The alternative sits on the side the flick goes to, the key's own sign moved off it.
                val side = if (hintBelow) 1f else -1f
                val shifted = floatArrayOf(position[0], position[1] - side * cellWidth * 0.12f)
                drawLabel(canvas, Core.label(handle, index), shifted, cellWidth)
                hintPaint.textSize = cellWidth * 0.36f
                drawCentred(canvas, hint, position[0], position[1] + side * cellWidth * 0.2f, hintPaint)
            }
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

    /**
     * The settings button at the left end, a slot as wide at the right end
     * for the autofill service's button, and three equal word slots between
     * them, the middle one bold; long words shrink to fit their slot.
     */
    private fun drawStrip(canvas: Canvas) {
        val middle = headroom + stripHeight / 2f
        drawGear(canvas, stripSide / 2f, middle, stripHeight * 0.22f)
        if (chipsShown) return
        val slotWidth = (width - 2f * stripSide) / 3f
        for ((index, word) in slots.withIndex()) {
            if (word.isEmpty()) continue
            stripPaint.isFakeBoldText = index == 1
            stripPaint.textSize = 17 * resources.displayMetrics.scaledDensity
            val room = slotWidth - 12 * density
            val measured = stripPaint.measureText(word)
            if (measured > room) stripPaint.textSize *= room / measured
            val baseline = middle - (stripPaint.descent() + stripPaint.ascent()) / 2
            canvas.drawText(word, stripSide + slotWidth * (index + 0.5f), baseline, stripPaint)
        }
        for (index in 1..2) {
            val x = stripSide + slotWidth * index
            canvas.drawLine(x, headroom + stripHeight * 0.3f, x, headroom + stripHeight * 0.7f, dividerPaint)
        }
    }

    /** A cog of eight teeth around a hole, in the label colour. */
    private fun drawGear(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val gear = Path()
        val teeth = 8
        for (i in 0 until teeth * 4) {
            val angle = Math.PI * 2 * i / (teeth * 4)
            val r = if ((i / 2) % 2 == 0) radius else radius * 0.75f
            val x = cx + r * Math.cos(angle).toFloat()
            val y = cy + r * Math.sin(angle).toFloat()
            if (i == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
        }
        gear.close()
        gear.addCircle(cx, cy, radius * 0.32f, Path.Direction.CCW)
        gear.fillType = Path.FillType.EVEN_ODD
        stripPaint.style = Paint.Style.FILL
        canvas.drawPath(gear, stripPaint)
    }

    /** The word slot at x, -1 for the settings button, 3 for the right end slot. */
    private fun stripSlot(x: Float): Int = when {
        x < stripSide -> -1
        x >= width - stripSide -> 3
        else -> ((x - stripSide) / ((width - 2f * stripSide) / 3f)).toInt().coerceIn(0, 2)
    }

    /** Single characters large, words such as "space" smaller so they fit their key. */
    private fun drawLabel(canvas: Canvas, label: String, position: FloatArray, cellWidth: Float) {
        val single = label.codePointCount(0, label.length) == 1
        labelPaint.textSize = cellWidth * if (single) 0.36f else 0.24f
        if (single && !label[0].isLetterOrDigit()) {
            drawCentred(canvas, label, position[0], position[1], labelPaint)
            return
        }
        val baseline = position[1] - (labelPaint.descent() + labelPaint.ascent()) / 2
        canvas.drawText(label, position[0], baseline, labelPaint)
    }

    private val glyphBounds = Rect()

    /**
     * Draws text centred on its own ink rather than on the line it sits on:
     * a full stop or comma sits low on the line and would look misplaced.
     */
    private fun drawCentred(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        paint.getTextBounds(text, 0, text.length, glyphBounds)
        canvas.drawText(text, x, y - glyphBounds.exactCenterY(), paint)
    }

    private fun path(corners: FloatArray) = Path().apply {
        moveTo(corners[0], corners[1])
        for (i in 2 until corners.size step 2) lineTo(corners[i], corners[i + 1])
        close()
    }

    /** The text before the cursor for the gesture log, null where it must not be kept. */
    var logContext: () -> String? = { null }

    /** The touch being written down for [GestureLog], in debug builds. */
    private var record: JSONObject? = null
    private var recordPoints = JSONArray()
    private var recordActions = JSONArray()
    private var recordDown = 0L
    private val logging = GestureLog.enabled(context)

    /** Adds what came of the touch being recorded, such as the words a swipe spelled. */
    fun note(kind: String, text: String) {
        if (record != null) recordActions.put(JSONObject().put("kind", kind).put("text", text))
    }

    private fun startRecord(x: Float, y: Float, time: Long) {
        if (!logging) return
        recordPoints = JSONArray()
        recordActions = JSONArray()
        recordDown = time
        val pressed = Core.pressed(handle)
        record = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("layout", Dictionaries.preferences(context).getString(LAYOUT, "hexkeys"))
            .put("width", width)
            .put("cellWidth", Core.cellWidth(handle).toDouble())
            .put("key", if (pressed >= 0) Core.label(handle, pressed) else "")
            .put("capital", Dictionaries.preferences(context).getString(CAPITAL, CAPITALS.first()))
            .put("deleteFromLeft", Dictionaries.preferences(context).getBoolean(DELETE_FROM_LEFT, false))
            .put("before", logContext())
        addPoint(x, y, time)
    }

    private fun addPoint(x: Float, y: Float, time: Long) {
        if (record != null) recordPoints.put(JSONArray().put(x.toInt()).put(y.toInt()).put(time - recordDown))
    }

    private fun finishRecord(how: String) {
        val done = record ?: return
        record = null
        done.put("end", how).put("points", recordPoints).put("actions", recordActions)
        GestureLog.write(context, done)
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
                    lift(fingerX, fingerY, time, "rollover")
                }
                val x = event.getX(index)
                val y = event.getY(index) - keysTop
                stripTouch = if (y < 0) stripSlot(x) + 1 else -1
                if (stripTouch >= 0) {
                    finger = -1
                    return true
                }
                finger = event.getPointerId(index)
                fingerX = x
                fingerY = y
                Core.touchDown(handle, x, y, time)
                startRecord(x, y, time)
            }
            MotionEvent.ACTION_MOVE -> {
                val at = event.findPointerIndex(finger)
                if (at < 0) return true
                fingerX = event.getX(at)
                fingerY = event.getY(at) - keysTop
                addPoint(fingerX, fingerY, time)
                val action = Core.touchMove(handle, fingerX, fingerY, time)
                if (action[0] != "nothing") act(action)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val x = event.getX(index)
                val y = event.getY(index) - keysTop
                if (stripTouch >= 0 && event.actionMasked == MotionEvent.ACTION_UP) {
                    val slot = stripSlot(x)
                    val touched = stripTouch - 1
                    stripTouch = -1
                    if (slot != touched || y >= 0) return true
                    if (slot == -1) {
                        onSettings()
                        return true
                    }
                    val word = slots.getOrElse(slot) { "" }
                    if (!chipsShown && word.isNotEmpty()) {
                        onPick(word, replaces)
                        if (logging) {
                            GestureLog.write(context, JSONObject().put("time", System.currentTimeMillis()).put("picked", word))
                        }
                    }
                    return true
                }
                if (event.getPointerId(index) == finger) lift(x, y, time, "lift")
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                Core.touchCancel(handle)
                finishRecord("cancel")
                finger = -1
                stripTouch = -1
            }
        }
        schedule()
        invalidate()
        return true
    }

    /** Ends the followed finger's touch at a point and acts on it. */
    private fun lift(x: Float, y: Float, time: Long, how: String) {
        finger = -1
        addPoint(x, y, time)
        act(Core.touchUp(handle, x, y, time))
        finishRecord(how)
    }

    private fun act(action: Array<String>) {
        val (kind, text) = action
        note(kind, text)
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

        /** Height of the suggestion strip in dp. */
        const val STRIP_DP = 44

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
