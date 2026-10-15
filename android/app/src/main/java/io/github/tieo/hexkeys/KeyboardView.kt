package io.github.tieo.hexkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
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
    private var pressed = -1

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

    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val openPopup = Runnable {
        if (pressed >= 0 && Core.longPress(handle, pressed)) invalidate()
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
        handle = Core.create(width.toFloat(), STRETCH)
        headroom = Core.headroom(handle).toInt()
        paths = (0 until Core.keyCount(handle)).map { path(Core.outline(handle, it)) }
        setMeasuredDimension(width, headroom + Core.height(handle).toInt() + navigationBar)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(openPopup)
        if (handle != 0L) Core.destroy(handle)
        handle = 0L
    }

    override fun onDraw(canvas: Canvas) {
        if (handle == 0L) return
        canvas.drawRect(0f, headroom.toFloat(), width.toFloat(), height.toFloat(), backgroundPaint)
        canvas.save()
        canvas.translate(0f, headroom.toFloat())
        labelPaint.textSize = width / 11.5f * 0.42f
        for ((index, path) in paths.withIndex()) {
            canvas.drawPath(path, if (index == pressed) pressedPaint else keyPaint)
            canvas.drawPath(path, gapPaint)
            drawLabel(canvas, Core.label(handle, index), Core.labelPosition(handle, index))
        }
        val choices = Core.popupCount(handle)
        if (choices > 0) {
            val selected = Core.popupSelected(handle)
            for (index in 0 until choices) {
                val path = path(Core.popupOutline(handle, index))
                canvas.drawPath(path, if (index == selected) selectedPaint else popupPaint)
                canvas.drawPath(path, gapPaint)
                drawLabel(canvas, Core.popupLabel(handle, index), Core.popupLabelPosition(handle, index))
            }
        }
        canvas.restore()
    }

    private fun drawLabel(canvas: Canvas, label: String, position: FloatArray) {
        val baseline = position[1] - (labelPaint.descent() + labelPaint.ascent()) / 2
        canvas.drawText(label, position[0], baseline, labelPaint)
    }

    private fun path(corners: FloatArray) = Path().apply {
        moveTo(corners[0], corners[1])
        for (i in 2 until corners.size step 2) lineTo(corners[i], corners[i + 1])
        close()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handle == 0L) return false
        val x = event.x
        val y = event.y - headroom
        val popupOpen = Core.popupCount(handle) > 0
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = Core.keyAt(handle, x, y)
                postDelayed(openPopup, longPressTimeout)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (popupOpen) {
                    Core.popupMove(handle, x, y)
                } else {
                    val key = Core.keyAt(handle, x, y)
                    if (key != pressed) {
                        // Sliding onto another key restarts the long-press wait there.
                        pressed = key
                        removeCallbacks(openPopup)
                        postDelayed(openPopup, longPressTimeout)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(openPopup)
                val (kind, text) = if (popupOpen) {
                    Core.popupRelease(handle)
                } else {
                    val key = Core.keyAt(handle, x, y)
                    if (key >= 0) Core.press(handle, key) else arrayOf("redraw", "")
                }
                pressed = -1
                onAction(kind, text)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(openPopup)
                Core.popupCancel(handle)
                pressed = -1
                invalidate()
            }
        }
        return true
    }

    private companion object {
        /** Cells 1.3 times as tall as a regular hexagon, for a finger-sized height. */
        const val STRETCH = 1.3f
    }
}
