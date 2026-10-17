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
 * The view is taller than the keys by the core's headroom, a transparent band
 * on top where a long-press popup over the top row can appear. The input
 * method reports only the keys as its content and touchable area, so the band
 * neither pushes the app up nor takes its touches.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val onAction: (kind: String, text: String) -> Unit,
) : View(context) {

    private var handle = 0L
    private var paths: List<Path> = emptyList()

    /** Height of the transparent band above the keys. */
    var headroom = 0
        private set

    /** Room below the keys for the navigation bar, which the window draws under. */
    private var navigationBar = 0

    private val density = resources.displayMetrics.density
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
        setMeasuredDimension(width, headroom + Core.height(handle).toInt() + navigationBar)
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
        canvas.save()
        canvas.translate(0f, headroom.toFloat())
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
        val y = event.y - headroom
        val time = event.eventTime
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
