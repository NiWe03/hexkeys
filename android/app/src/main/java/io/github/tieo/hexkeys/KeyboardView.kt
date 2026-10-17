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
    private val onEmoji: () -> Unit,
    private val onPasswords: () -> Unit,
    private val onVoice: () -> Unit,
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

    /** The obvious fix of the typed word, such as its capital, with what it replaces. */
    var fix: Pair<String, Int>? = null
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

    /** Speech input's state: listening since [listeningSince], and stretches still being recognised. */
    var listening = false
        set(value) {
            val starting = value && !field
            field = value
            if (starting) {
                listeningSince = SystemClock.uptimeMillis()
                levels.clear()
            }
            invalidate()
        }
    var recognising = 0
        set(value) {
            field = value
            invalidate()
        }
    private var listeningSince = 0L

    /** The loudness of the latest tenths of a second, the newest last, for the waveform. */
    private val levels = ArrayDeque<Float>()

    fun voiceLevel(level: Float) {
        levels.addLast(level)
        while (levels.size > LEVELS) levels.removeFirst()
        invalidate()
    }

    /** What the delete slide under way has taken, shown in the strip while it lasts. */
    var deletedText = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Enter's icon, and the small one for what holding it does, from the field's action. */
    var enterIcon = R.drawable.icon_keyboard_return
        set(value) {
            field = value
            invalidate()
        }
    var enterHint: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Whether a password manager is set up, which the key button at the strip's right end opens. */
    var passwordsAvailable = false
        set(value) {
            field = value
            invalidate()
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
    var navigationBar = 0
        private set

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
    private val deletePaint = Paint()
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
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
        spinnerPaint.color = theme.accent
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
        handle = Core.create(
            width.toFloat(), STRETCH, ViewConfiguration.getLongPressTimeout().toLong(), space_middle,
            preferences.getBoolean(EDIT_KEYS, false),
        )
        // Freed on the words thread after whatever it is still doing with it.
        val old = letters
        letters = Core.letters(handle)
        if (old != 0L) Core.words.execute { Core.lettersFree(old) }
        Core.setAccents(handle, accents)
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

    /**
     * The keyboard's letter layer for the [Core.words] thread, copied anew
     * with every keyboard; 0 before the first.
     */
    var letters = 0L
        private set

    /** The text before the cursor the strip shown was worked out for. */
    var stripBefore: String? = null
        private set

    /** Shows a strip the core worked out for `before`. */
    fun showStrip(before: String, strip: Array<String>) {
        stripBefore = before
        slots = arrayOf(strip[0], strip[1], strip[2])
        replaces = strip[3].toInt()
        correction = strip[4].takeIf { it.isNotEmpty() }?.let { it to replaces }
        unknown = strip[5].takeIf { it.isNotEmpty() }
        fix = strip[6].takeIf { it.isNotEmpty() }?.let { it to replaces }
        invalidate()
    }

    /** The last swipe's path for [Core.decodeSwipe]. */
    fun swiped(): FloatArray = if (handle == 0L) FloatArray(0) else Core.swiped(handle)

    /** Shows a swipe's words, the one committed in the middle; a choice replaces it. */
    fun showSwipe(words: Array<String>) {
        stripBefore = null
        slots = arrayOf(words.getOrElse(1) { "" }, words[0], words.getOrElse(2) { "" })
        replaces = words[0].codePointCount(0, words[0].length)
        correction = null
        fix = null
        unknown = null
        invalidate()
    }

    fun clearSuggestions() {
        stripBefore = null
        slots = arrayOf("", "", "")
        replaces = 0
        correction = null
        fix = null
        unknown = null
        invalidate()
    }

    /** Every layer tiles the same area, so a layer change only swaps the outlines. */
    private fun loadOutlines() {
        paths = (0 until Core.keyCount(handle)).map { path(Core.outline(handle, it)) }
        // Corners rounded in drawing only; touches still go by the sharp hexagons.
        val corners = android.graphics.CornerPathEffect(Core.cellWidth(handle) * CORNER)
        for (paint in listOf(keyPaint, gapPaint, popupPaint, selectedPaint)) paint.pathEffect = corners
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
        if (handle != 0L) Core.destroy(handle)
        handle = 0L
        val old = letters
        letters = 0L
        if (old != 0L) Core.words.execute { Core.lettersFree(old) }
    }

    override fun onDraw(canvas: Canvas) {
        if (handle == 0L) return
        canvas.drawRect(0f, headroom.toFloat(), width.toFloat(), height.toFloat(), backgroundPaint)
        drawStrip(canvas)
        canvas.save()
        canvas.translate(0f, keysTop.toFloat())
        val cellWidth = Core.cellWidth(handle)
        val pressed = Core.pressed(handle)
        // Holding space turns the keys into a touchpad for the cursor: their labels fade.
        labelPaint.alpha = if (Core.movingCursor(handle)) 0x40 else 0xFF
        for ((index, path) in paths.withIndex()) {
            val color = keyColor(index)
            keyPaint.color = if (index == pressed || index == flashKey) theme.pressed(color) else color
            canvas.drawPath(path, keyPaint)
            canvas.drawPath(path, gapPaint)
            val position = Core.labelPosition(handle, index)
            val hint = Core.keyHint(handle, index)
            val label = Core.label(handle, index)
            val enterSmall = enterHint
            if (label == "⏎" && enterSmall != null) {
                // What holding enter does, small above the return arrow.
                drawIcon(canvas, enterIcon, position[0], position[1] + cellWidth * 0.12f, cellWidth * 0.32f, labelPaint.color)
                drawIcon(canvas, enterSmall, position[0], position[1] - cellWidth * 0.24f, cellWidth * 0.22f, hintPaint.color)
            } else if (hint.isEmpty()) {
                drawLabel(canvas, Core.label(handle, index), position, cellWidth)
            } else {
                // The alternative sits on the side the flick goes to, the key's own sign moved off it.
                val side = -1f
                val shifted = floatArrayOf(position[0], position[1] - side * cellWidth * 0.14f)
                drawLabel(canvas, Core.label(handle, index), shifted, cellWidth)
                hintPaint.textSize = cellWidth * 0.28f
                drawCentred(canvas, hint, position[0], position[1] + side * cellWidth * 0.26f, hintPaint)
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
        // A delete slide washes the keys it has crossed, from the edge to the finger.
        val deleting = Core.deleting(handle)
        if (deleting.size == 2) {
            val fromLeft = deleting[1] > deleting[0]
            val edge = if (fromLeft) 0f else width.toFloat()
            deletePaint.color = DELETE_COLOR
            canvas.drawRect(minOf(edge, deleting[1]), 0f, maxOf(edge, deleting[1]), Core.height(handle), deletePaint)
            drawIcon(canvas, R.drawable.icon_backspace, deleting[1], fingerY, cellWidth * 0.45f, theme.label)
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
        val iconSize = stripHeight * 0.55f
        drawIcon(canvas, R.drawable.icon_settings, stripSide / 2f, middle, iconSize, theme.label)
        drawIcon(canvas, R.drawable.icon_mood, stripSide * 1.5f, middle, iconSize, theme.label)
        if (passwordsAvailable && !chipsShown) {
            drawIcon(canvas, R.drawable.icon_key, width - stripSide / 2f, middle, iconSize, theme.label)
        }
        val micX = width - stripSide * 1.5f
        if (listening) {
            drawIcon(canvas, R.drawable.icon_stop_circle, micX, middle, iconSize * 1.15f, theme.accent)
        } else {
            drawIcon(canvas, R.drawable.icon_mic, micX, middle, iconSize, theme.label)
        }
        if (recognising > 0) {
            // A turning arc around the button while speech is still being turned into text.
            val radius = iconSize * 0.8f
            val turn = (SystemClock.uptimeMillis() % 1000) * 0.36f
            canvas.drawArc(micX - radius, middle - radius, micX + radius, middle + radius, turn, 100f, false, spinnerPaint)
            postInvalidateOnAnimation()
        }
        if (listening || recognising > 0) {
            drawDictation(canvas, middle)
            return
        }
        if (Core.deleting(handle).isNotEmpty() && deletedText.isNotEmpty()) {
            drawDeleted(canvas, middle)
            return
        }
        if (chipsShown) return
        val slotWidth = (width - 4f * stripSide) / 3f
        for ((index, word) in slots.withIndex()) {
            if (word.isEmpty()) continue
            stripPaint.isFakeBoldText = index == 1
            stripPaint.textSize = 17 * resources.displayMetrics.scaledDensity
            val room = slotWidth - 12 * density
            val measured = stripPaint.measureText(word)
            if (measured > room) stripPaint.textSize *= room / measured
            val baseline = middle - (stripPaint.descent() + stripPaint.ascent()) / 2
            canvas.drawText(word, 2 * stripSide + slotWidth * (index + 0.5f), baseline, stripPaint)
        }
        for (index in 1..2) {
            val x = 2 * stripSide + slotWidth * index
            canvas.drawLine(x, headroom + stripHeight * 0.3f, x, headroom + stripHeight * 0.7f, dividerPaint)
        }
    }

    /**
     * In place of the words while dictating: the voice as bars, newest at
     * the right, and how long it has been listening; the bars dim once
     * listening stops and only the recognising is left.
     */
    private fun drawDictation(canvas: Canvas, middle: Float) {
        val left = 2 * stripSide + 8 * density
        stripPaint.isFakeBoldText = false
        stripPaint.textSize = 15 * resources.displayMetrics.scaledDensity
        val seconds = ((SystemClock.uptimeMillis() - listeningSince) / 1000).toInt()
        val clock = "%d:%02d".format(seconds / 60, seconds % 60)
        val clockWidth = stripPaint.measureText("00:00")
        val right = width - 2 * stripSide - clockWidth - 12 * density
        if (listening) {
            canvas.drawText(clock, right + 6 * density + clockWidth / 2, middle - (stripPaint.descent() + stripPaint.ascent()) / 2, stripPaint)
            postInvalidateDelayed(250)
        }
        val step = (right - left) / LEVELS
        wavePaint.color = theme.accent
        wavePaint.alpha = if (listening) 0xFF else 0x60
        wavePaint.strokeWidth = step * 0.6f
        val tallest = stripHeight * 0.32f
        for ((index, level) in levels.withIndex()) {
            val x = right - (levels.size - index - 0.5f) * step
            // Loudness spans a wide range; its square root shows quiet speech too.
            val half = maxOf(1.5f * density, tallest * kotlin.math.sqrt((level * 8f).coerceAtMost(1f)))
            canvas.drawLine(x, middle - half, x, middle + half, wavePaint)
        }
    }

    /** In place of the words during a delete slide: what it has taken so far, struck through, its end at the right. */
    private fun drawDeleted(canvas: Canvas, middle: Float) {
        val left = 2 * stripSide + 8 * density
        val right = width - 2 * stripSide - 8 * density
        stripPaint.isFakeBoldText = false
        stripPaint.textSize = 17 * resources.displayMetrics.scaledDensity
        var shown = deletedText
        while (shown.length > 1 && stripPaint.measureText("…$shown") > right - left) shown = shown.drop(1)
        if (shown != deletedText) shown = "…$shown"
        val textWidth = stripPaint.measureText(shown)
        val x = right - textWidth / 2
        val baseline = middle - (stripPaint.descent() + stripPaint.ascent()) / 2
        stripPaint.color = DELETE_TEXT
        stripPaint.isStrikeThruText = true
        canvas.drawText(shown, x, baseline, stripPaint)
        stripPaint.isStrikeThruText = false
        stripPaint.color = theme.label
    }

    private val icons = mutableMapOf<Int, android.graphics.drawable.Drawable>()

    /** Draws one of the Material Symbols icons centred on a point, `size` across, tinted. */
    private fun drawIcon(canvas: Canvas, id: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val icon = icons.getOrPut(id) { context.getDrawable(id)!!.mutate() }
        icon.setTint(color)
        val half = size / 2
        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        icon.draw(canvas)
    }

    /**
     * The strip's part at x: -2 the settings button and -1 the emoji button
     * at its left end, 0 to 2 the word slots, 4 the microphone and 3 the
     * right end, which holds the password manager's button.
     */
    private fun stripSlot(x: Float): Int = when {
        x < stripSide -> -2
        x < 2 * stripSide -> -1
        x >= width - stripSide -> 3
        x >= width - 2 * stripSide -> 4
        else -> ((x - 2 * stripSide) / ((width - 4f * stripSide) / 3f)).toInt().coerceIn(0, 2)
    }

    /** Single characters large, words such as "space" smaller so they fit their key. */
    private fun drawLabel(canvas: Canvas, label: String, position: FloatArray, cellWidth: Float) {
        // Space is the long key; it needs no name.
        if (label == "space") return
        (if (label == "⏎") enterIcon else KEY_ICONS[label])?.let { id ->
            drawIcon(canvas, id, position[0], position[1], cellWidth * 0.36f, labelPaint.color)
            return
        }
        val single = label.codePointCount(0, label.length) == 1
        labelPaint.textSize = cellWidth * if (single) 0.36f else 0.24f
        if (single && label in INK_CENTRED) {
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

    /**
     * Writes down a step that held up the main thread for [SLOW_MS] or
     * more, so lag on the phone shows in the log with what caused it.
     */
    fun slow(step: String, ms: Long) {
        if (logging && ms >= SLOW_MS) {
            GestureLog.write(context, JSONObject().put("time", System.currentTimeMillis()).put("slow", step).put("ms", ms))
        }
    }

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
            .put("editKeys", Dictionaries.preferences(context).getBoolean(EDIT_KEYS, false))
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
                stripTouch = if (y < 0) stripSlot(x) + 2 else -1
                if (stripTouch >= 0) {
                    finger = -1
                    return true
                }
                finger = event.getPointerId(index)
                downTime = time
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
                    val touched = stripTouch - 2
                    stripTouch = -1
                    if (slot != touched || y >= 0) return true
                    if (slot == -2) {
                        onSettings()
                        return true
                    }
                    if (slot == -1) {
                        onEmoji()
                        return true
                    }
                    if (slot == 3) {
                        if (passwordsAvailable) onPasswords()
                        return true
                    }
                    if (slot == 4) {
                        onVoice()
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

    /** When the followed finger went down. */
    private var downTime = 0L

    /** The key lit after its tap has ended, until [FLASH_MS] after it went down, or -1. */
    private var flashKey = -1
    private val unflash = Runnable {
        flashKey = -1
        invalidate()
    }

    /** Ends the followed finger's touch at a point and acts on it. */
    private fun lift(x: Float, y: Float, time: Long, how: String) {
        finger = -1
        // A tap quicker than a frame or two would never be seen lit.
        val pressed = Core.pressed(handle)
        if (pressed >= 0) {
            flashKey = pressed
            removeCallbacks(unflash)
            postDelayed(unflash, (downTime + FLASH_MS - time).coerceIn(FLASH_MS / 2, FLASH_MS))
        }
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

        /** Keys drawn as an icon instead of their label. */
        val KEY_ICONS = mapOf(
            "⌫" to R.drawable.icon_backspace,
            "⏎" to R.drawable.icon_keyboard_return,
            "⇧" to R.drawable.icon_shift,
        )

        /** Marks that sit high or low on the line and look misplaced unless centred on their ink. */
        private val INK_CENTRED = setOf(".", ",", ";", ":", "'", "\"", "…", "!", "?", "¡", "¿")

        /** The wash over keys a delete slide has crossed, and the colour of the text it took. */
        private const val DELETE_COLOR = 0x40E5484D
        private const val DELETE_TEXT = 0xFFFF6B6B.toInt()

        /** How round the keys' corners are drawn, in key widths. */
        const val CORNER = 0.12f

        /** Tenths of a second of voice the waveform shows. */
        private const val LEVELS = 48

        /** Main thread time from which a step counts as slow in the gesture log, in ms. */
        private const val SLOW_MS = 8L

        /** How long a tapped key stays lit at least, so a quick tap shows, in ms. */
        private const val FLASH_MS = 90L

        /** Height of the suggestion strip in dp. */
        const val STRIP_DP = 44

        /** Width of the delete edge kept from the back gesture, in dp. */
        private const val EXCLUSION_WIDTH = 32

        /** Preference naming the letter layout; [SPACE_MIDDLE] or anything else for the hexkeys one. */
        const val LAYOUT = "layout"
        const val SPACE_MIDDLE = "space_middle"

        /** Preference: delete swipes come in from the left edge. */
        const val DELETE_FROM_LEFT = "delete_from_left"

        /** Preference adding shift and backspace keys to the layout. */
        const val EDIT_KEYS = "edit_keys"

        /** Preference naming the capital gesture, one of [CAPITALS] in the core's order. */
        const val CAPITAL = "capital"
        val CAPITALS = listOf("flick_up", "hold")
    }
}
