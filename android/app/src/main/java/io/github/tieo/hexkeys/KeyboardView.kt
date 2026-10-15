package io.github.tieo.hexkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets

/**
 * Draws the keys the core lays out and passes touches back to it.
 *
 * Outlines come from the core and tile the whole view; each is drawn inset
 * by a hairline stroke in the background colour, which reads as the gap
 * between keys without changing where a touch lands.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val onAction: (kind: String, text: String) -> Unit,
) : View(context) {

    private var handle = 0L
    private var paths: List<Path> = emptyList()
    private var pressed = -1

    /** Room below the keys for the navigation bar, which the window draws under. */
    private var navigationBar = 0

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

    private val density = resources.displayMetrics.density
    private val background = 0xFF1B1B1F.toInt()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A42.toInt() }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF55555F.toInt() }
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (handle != 0L) Core.destroy(handle)
        handle = Core.create(width.toFloat(), STRETCH)
        paths = (0 until Core.keyCount(handle)).map { index ->
            val corners = Core.outline(handle, index)
            Path().apply {
                moveTo(corners[0], corners[1])
                for (i in 2 until corners.size step 2) lineTo(corners[i], corners[i + 1])
                close()
            }
        }
        setMeasuredDimension(width, Core.height(handle).toInt() + navigationBar)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (handle != 0L) Core.destroy(handle)
        handle = 0L
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        if (handle == 0L) return
        labelPaint.textSize = width / 11.5f * 0.42f
        for ((index, path) in paths.withIndex()) {
            canvas.drawPath(path, if (index == pressed) pressedPaint else keyPaint)
            canvas.drawPath(path, gapPaint)
            val (x, y) = Core.labelPosition(handle, index)
            val label = Core.label(handle, index)
            val baseline = y - (labelPaint.descent() + labelPaint.ascent()) / 2
            canvas.drawText(label, x, baseline, labelPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handle == 0L) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val key = Core.keyAt(handle, event.x, event.y)
                if (key != pressed) {
                    pressed = key
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val key = Core.keyAt(handle, event.x, event.y)
                pressed = -1
                if (key >= 0) {
                    val (kind, text) = Core.press(handle, key)
                    onAction(kind, text)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
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
