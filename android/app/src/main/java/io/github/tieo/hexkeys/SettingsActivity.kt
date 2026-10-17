package io.github.tieo.hexkeys

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * The keyboard's settings, which the launcher and the system's keyboard
 * settings both open: setting the keyboard up while it is not the active
 * one, a field to try it in, typing, gestures, looks, and the languages,
 * which have a page of their own.
 */
class SettingsActivity : Activity() {

    companion object {
        /** Extra naming the part of the settings to show, such as [VOICE]. */
        const val SECTION = "section"
        const val VOICE = "voice"
    }

    private lateinit var setup: View
    private lateinit var setupButton: Button
    private lateinit var languages: TextView
    private val ui by lazy { Ui(this) }
    private val downloads = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = ui.column()

        setupButton = ui.primaryButton("")
        setup = ui.card(setupButton)
        column.addView(setup)

        column.addView(ui.card(EditText(this).apply {
            hint = "Try the keyboard"
            setTextColor(ui.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minHeight = ui.dp(48)
        }))

        column.addView(ui.heading("Typing"))
        column.addView(ui.card(
            ui.label("Autocorrect"),
            ui.segmented(
                Dictionaries.AUTOCORRECT_MODES.zip(listOf("Off", "Obvious", "All")),
                Dictionaries.autocorrectMode(this),
            ) { id -> ui.preferences.edit().putString(Dictionaries.AUTOCORRECT_MODE, id).apply() },
            learningRow(),
        ))

        column.addView(ui.heading("Gestures"))
        column.addView(ui.card(
            ui.label("Delete by sliding in from"),
            ui.segmented(
                listOf("left" to "Left edge", "right" to "Right edge"),
                if (ui.preferences.getBoolean(KeyboardView.DELETE_FROM_LEFT, false)) "left" else "right",
            ) { id -> ui.preferences.edit().putBoolean(KeyboardView.DELETE_FROM_LEFT, id == "left").apply() },
            ui.label("Capital letter"),
            ui.segmented(
                KeyboardView.CAPITALS.zip(listOf("Flick up", "Hold")),
                ui.preferences.getString(KeyboardView.CAPITAL, null)?.takeIf { it in KeyboardView.CAPITALS }
                    ?: KeyboardView.CAPITALS.first(),
            ) { id -> ui.preferences.edit().putString(KeyboardView.CAPITAL, id).apply() },
        ))

        column.addView(ui.heading("Voice"))
        column.addView(voiceCard())

        column.addView(ui.heading("Look"))
        layouts = layoutPicker()
        column.addView(ui.card(
            ui.label("Layout"),
            layouts,
            ui.toggle("Shift and backspace keys", KeyboardView.EDIT_KEYS) { layouts.invalidateAll() },
            ui.label("Theme"),
            themePicker(),
        ))

        languages = TextView(this).apply {
            textSize = 15f
            setTextColor(ui.secondaryText)
        }
        column.addView(ui.card(ui.navigationRow("Languages", languages) {
            startActivity(Intent(this, LanguagesActivity::class.java))
        }).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(24) })

        val page = ui.page(column)
        setContentView(page)
        title = "Hexkeys"
        if (intent.getStringExtra(SECTION) == VOICE) showVoice(page)
    }

    /**
     * Opened from the microphone button while speaking is not set up yet:
     * the voice setting scrolled into view and lit up, and the microphone
     * asked for at once when a model is there already.
     */
    private fun showVoice(page: android.widget.ScrollView) {
        page.post {
            page.smoothScrollTo(0, voice.top - ui.dp(48))
            val highlight = GradientDrawable().apply {
                cornerRadius = ui.dp(20).toFloat()
                setColor(0x14FFFFFF)
                setStroke(ui.dp(2), ui.accent)
            }
            val plain = voice.background
            voice.background = highlight
            android.animation.ObjectAnimator.ofInt(highlight, "alpha", 255, 90, 255, 90, 255).apply {
                duration = 1600
                doOnEnd { voice.background = plain }
                start()
            }
        }
        if (!Voice.permitted(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        }
        // With no model yet, the default one starts downloading right away.
        if (Voice.chosen(this) == null) startDefault()
    }

    private fun android.animation.Animator.doOnEnd(action: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = action()
    })

    override fun onResume() {
        super.onResume()
        // Coming back from the system settings: show only the step still missing.
        val manager = getSystemService(InputMethodManager::class.java)
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        setup.visibility = if (enabled && current) View.GONE else View.VISIBLE
        setupButton.text = if (enabled) "Switch to Hexkeys" else "Enable Hexkeys"
        setupButton.setOnClickListener {
            if (enabled) manager.showInputMethodPicker()
            else startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        val chosen = Dictionaries.enabled(this)
        languages.text = Dictionaries.catalog(this).filter { it.file in chosen }.joinToString(", ") { it.name }
        microphone.visibility = if (Voice.permitted(this) || Voice.chosen(this) == null) View.GONE else View.VISIBLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard picker is a dialog over this window, not a new screen.
        if (hasFocus) onResume()
    }

    private lateinit var microphone: Button
    private lateinit var voice: View

    /** Downloads the default speech model, for the microphone button arriving with none. */
    private var startDefault: () -> Unit = {}
    private lateinit var layouts: LayoutTiles

    /**
     * The speech model to download, by size, with the download's progress,
     * and the button to allow the microphone while it is not allowed.
     */
    private fun voiceCard(): View {
        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        val chosen = Voice.chosen(this)?.id ?: "off"
        // A model downloaded before it could be timed is timed now.
        Voice.chosen(this)?.takeIf { Voice.timing(this, it) == null }?.let { model ->
            downloads.execute {
                runCatching { Voice.measure(this, model) }
                if (Voice.timing(this, model) != null) runOnUiThread { recreate() }
            }
        }
        // Each model with its size and, once one has been timed on this phone, how long a sentence takes.
        val options = listOf<Pair<String, CharSequence>>("off" to "Off") + Voice.MODELS.map { model ->
            val label = android.text.SpannableStringBuilder("${model.name}\n${model.megabytes} MB")
            Voice.expectedTiming(this, model)?.let { (ms, measured) ->
                label.append("\n")
                val icon = label.length
                label.append(" ")
                label.setSpan(IconSpan(getDrawable(R.drawable.icon_timer)!!), icon, icon + 1, 0)
                label.append(" ${if (measured) "" else "≈"}${"%.1f".format(ms / 1000f)} s")
            }
            model.id to label
        }
        // Downloaded models stay, so switching between them to compare costs nothing.
        fun fetch(model: VoiceModel) {
            progress.progress = 0
            progress.isIndeterminate = false
            progress.visibility = View.VISIBLE
            downloads.execute {
                val ok = runCatching {
                    Voice.download(this, model) { done -> runOnUiThread { progress.progress = (done * 1000).toInt() } }
                    runOnUiThread { progress.isIndeterminate = true }
                    Voice.measure(this, model)
                }.isSuccess
                runOnUiThread {
                    progress.visibility = View.GONE
                    if (ok) ui.preferences.edit().putString(Voice.MODEL, model.id).apply()
                    recreate()
                }
            }
        }
        startDefault = { fetch(VoiceModels.DEFAULT) }
        val choice = ui.segmented(options, chosen, columns = 3) { id ->
            val model = Voice.MODELS.firstOrNull { it.id == id }
            if (model == null || Voice.downloaded(this, model)) {
                ui.preferences.edit().putString(Voice.MODEL, model?.id).apply()
                if (model != null && Voice.timing(this, model) == null) recreate()
                return@segmented
            }
            fetch(model)
        }
        microphone = ui.primaryButton("Allow the microphone").apply {
            setOnClickListener { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1) }
        }
        voice = ui.card(choice, progress, microphone)
        return voice
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onResume()
    }

    /** The learning switch, with deleting what was learned beside it once there is something. */
    private fun learningRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val toggle = ui.toggle("Learn new words", Dictionaries.LEARNING)
        row.addView(toggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (Dictionaries.learned(this).isNotEmpty()) {
            val clear = ui.textButton("Forget")
            clear.setOnClickListener {
                Dictionaries.deleteLearned(this)
                clear.visibility = View.GONE
            }
            row.addView(clear)
        }
        return row
    }

    /** The layouts side by side as tiles with a drawing of their keys, the chosen one outlined. */
    private fun layoutPicker(): LayoutTiles {
        val tiles = LayoutTiles(this)
        val chosen = ui.preferences.getString(KeyboardView.LAYOUT, null) ?: "hexkeys"
        val views = mutableListOf<View>()
        for ((id, name) in listOf("hexkeys" to "Space at the bottom", KeyboardView.SPACE_MIDDLE to "Space in the middle")) {
            val preview = LayoutPreview(this, id == KeyboardView.SPACE_MIDDLE)
            tiles.previews += preview
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
                addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(this@SettingsActivity).apply {
                    text = name
                    textSize = 13f
                    setTextColor(ui.text)
                    gravity = Gravity.CENTER
                    setPadding(0, ui.dp(6), 0, 0)
                })
            }
            views += tile
            tile.setOnClickListener {
                views.forEach { ui.outline(it, it === tile) }
                ui.preferences.edit().putString(KeyboardView.LAYOUT, id).apply()
            }
            ui.outline(tile, id == chosen)
            tiles.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(ui.dp(2), 0, ui.dp(2), 0)
            })
        }
        return tiles
    }

    /** Every theme as a tile with its preview and name, the chosen one outlined. */
    private fun themePicker(): View {
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chosen = Theme.current(this).id
        val all = Theme.all(this)
        val views = mutableListOf<View>()
        for (theme in all) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
                addView(ThemePreview(this@SettingsActivity, theme), LinearLayout.LayoutParams(ui.dp(104), ui.dp(44)))
                addView(TextView(this@SettingsActivity).apply {
                    text = theme.name
                    textSize = 13f
                    setTextColor(ui.text)
                    gravity = Gravity.CENTER
                    setPadding(0, ui.dp(6), 0, 0)
                })
            }
            views += tile
            tile.setOnClickListener {
                views.forEach { ui.outline(it, it === tile) }
                ui.preferences.edit().putString(Theme.KEY, theme.id).apply()
                layouts.invalidateAll()
            }
            ui.outline(tile, theme.id == chosen)
            tiles.addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = ui.dp(8)
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tiles)
        }
    }
}

/**
 * The look the settings pages share: sections of rounded cards on the
 * window background, segmented buttons for short choices, the system's
 * accent colour where Android has one.
 */
class Ui(private val activity: Activity) {
    private val density = activity.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()

    val preferences get() = Dictionaries.preferences(activity)

    val accent: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        activity.getColor(android.R.color.system_accent1_200)
    } else {
        0xFF8AB4F8.toInt()
    }
    private val onAccent = 0xFF101318.toInt()

    /** Text colours: the system theme's own defaults are dim enough to look disabled. */
    val text = 0xFFECEDF1.toInt()
    val secondaryText = 0xFFC2C5CC.toInt()
    private val cardColor = 0x14FFFFFF
    private val idleColor = 0x1FFFFFFF

    fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(24))
    }

    /** A scrolling page around `content`, kept out from under the system bars. */
    fun page(content: View) = ScrollView(activity).apply {
        addView(content)
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    fun rounded(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    fun heading(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(accent)
        setPadding(dp(4), dp(20), 0, dp(8))
    }

    fun label(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 15f
        setTextColor(secondaryText)
        setPadding(0, dp(12), 0, dp(8))
    }

    fun card(vararg children: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cardColor, 20)
        setPadding(dp(16), dp(8), dp(16), dp(12))
        for (child in children) addView(child)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
    }

    fun toggle(label: String, key: String, changed: () -> Unit = {}) = Switch(activity).apply {
        text = label
        setTextColor(this@Ui.text)
        textSize = 17f
        minHeight = dp(52)
        isChecked = preferences.getBoolean(key, false)
        setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(key, checked).apply()
            changed()
        }
    }

    fun primaryButton(text: String) = Button(activity).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(onAccent)
        background = rounded(accent, 24)
        minHeight = dp(52)
    }

    fun textButton(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 15f
        setTextColor(accent)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(16), 0, dp(4), 0)
    }

    /** A row with a title and a summary below it that opens another page. */
    fun navigationRow(title: String, summary: TextView, open: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 17f
                setTextColor(this@Ui.text)
            })
            addView(summary)
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(activity).apply {
            text = "›"
            textSize = 24f
            setTextColor(secondaryText)
        })
        setOnClickListener { open() }
    }

    /**
     * Buttons side by side, `columns` to a row, of which one is chosen:
     * `options` are id to name, `chosen` the id chosen now; `choose` runs
     * when another is tapped.
     */
    fun segmented(
        options: List<Pair<String, CharSequence>>,
        chosen: String,
        columns: Int = options.size,
        choose: (String) -> Unit,
    ): View {
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val buttons = mutableListOf<Pair<String, TextView>>()
        // Every button as tall as the label with the most lines, counting a long one as wrapping once.
        val width = activity.resources.displayMetrics.widthPixels / density / columns
        val lines = options.maxOf { (_, name) ->
            name.toString().split('\n').sumOf { line -> if (line.length * 8.5f > width - 24) 2 else 1 }
        }
        val tallest = dp(24 + 20 * lines)
        fun paint(current: String) {
            for ((id, button) in buttons) {
                val on = id == current
                button.background = rounded(if (on) accent else idleColor, 12)
                button.setTextColor(if (on) onAccent else 0xFFE8E8EC.toInt())
            }
        }
        for (start in options.indices step columns) {
            // Not aligned on the text's baseline, which drops a two-line button below its neighbour.
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            for ((id, name) in options.subList(start, minOf(start + columns, options.size))) {
                val button = TextView(activity).apply {
                    text = name
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(dp(6), 0, dp(6), 0)
                    setOnClickListener {
                        paint(id)
                        choose(id)
                    }
                }
                buttons += id to button
                row.addView(button, LinearLayout.LayoutParams(0, tallest, 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            grid.addView(row)
        }
        paint(chosen)
        return grid
    }

    /** Marks a tile as chosen with an accent outline. */
    fun outline(view: View, on: Boolean) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(2), if (on) accent else 0x00000000)
        }
    }
}

/**
 * A strip of the keyboard in a theme's colours: one hexagon per QWERTZ row
 * colour and one in the colour of the other keys, on the theme's background.
 */
private class ThemePreview(context: Context, private val theme: Theme) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corner = resources.displayMetrics.density * 10

    override fun onDraw(canvas: Canvas) {
        paint.color = theme.background
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), corner, corner, paint)
        val colors = theme.rows + theme.other
        val gap = resources.displayMetrics.density * 4
        val cell = (width - gap) / colors.size
        val radius = minOf(cell - gap, height - 2 * gap) / 2f
        for ((index, color) in colors.withIndex()) {
            paint.color = color
            val cx = gap / 2 + cell * (index + 0.5f)
            val cy = height / 2f
            val hexagon = Path()
            for (corner in 0 until 6) {
                val angle = Math.toRadians(60.0 * corner - 90)
                val x = cx + radius * Math.cos(angle).toFloat()
                val y = cy + radius * Math.sin(angle).toFloat()
                if (corner == 0) hexagon.moveTo(x, y) else hexagon.lineTo(x, y)
            }
            hexagon.close()
            canvas.drawPath(hexagon, paint)
        }
    }
}

/** The layout tiles in a row, with their drawings to redraw when the keys change. */
private class LayoutTiles(context: Context) : LinearLayout(context) {
    val previews = mutableListOf<LayoutPreview>()

    fun invalidateAll() = previews.forEach { it.requestLayout() }
}

/**
 * A layout's keys as the keyboard draws them, in the chosen theme, shrunk
 * to the width it is given; with shift and backspace when they are set up.
 */
private class LayoutPreview(context: Context, private val space_middle: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val gap = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var handle = 0L
    private var theme = Theme.current(context)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (handle != 0L) Core.destroy(handle)
        val editKeys = Dictionaries.preferences(context).getBoolean(KeyboardView.EDIT_KEYS, false)
        handle = Core.create(width.toFloat(), 1f, 400, space_middle, editKeys)
        theme = Theme.current(context)
        setMeasuredDimension(width, Core.height(handle).toInt())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (handle != 0L) Core.destroy(handle)
        handle = 0L
    }

    override fun onDraw(canvas: Canvas) {
        if (handle == 0L) return
        val corner = resources.displayMetrics.density * 8
        paint.color = theme.background
        paint.pathEffect = null
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), corner, corner, paint)
        val cell = Core.cellWidth(handle)
        gap.color = theme.background
        gap.strokeWidth = cell * 0.06f
        val corners = android.graphics.CornerPathEffect(cell * KeyboardView.CORNER)
        paint.pathEffect = corners
        gap.pathEffect = corners
        labels.color = theme.label
        for (index in 0 until Core.keyCount(handle)) {
            val corners = Core.outline(handle, index)
            val path = Path()
            path.moveTo(corners[0], corners[1])
            for (i in 2 until corners.size step 2) path.lineTo(corners[i], corners[i + 1])
            path.close()
            val row = Core.keyRow(handle, index)
            paint.color = if (row in theme.rows.indices) theme.rows[row] else theme.other
            canvas.drawPath(path, paint)
            canvas.drawPath(path, gap)
            val (x, y) = Core.labelPosition(handle, index).let { it[0] to it[1] }
            val label = Core.label(handle, index)
            if (label == "space") continue
            val icon = KeyboardView.KEY_ICONS[label]
            if (icon != null) {
                val drawable = context.getDrawable(icon)!!.mutate()
                drawable.setTint(theme.label)
                val half = (cell * 0.2f).toInt()
                drawable.setBounds(x.toInt() - half, y.toInt() - half, x.toInt() + half, y.toInt() + half)
                drawable.draw(canvas)
                continue
            }
            labels.textSize = cell * if (label.codePointCount(0, label.length) == 1) 0.4f else 0.24f
            canvas.drawText(label, x, y - (labels.descent() + labels.ascent()) / 2, labels)
        }
    }
}

/** An icon in a line of text, as tall as the text and in its colour, which follows the button's state. */
private class IconSpan(private val icon: android.graphics.drawable.Drawable) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) =
        (paint.textSize * 1.05f).toInt()

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val size = (paint.textSize * 1.05f).toInt()
        val iconTop = y + paint.fontMetricsInt.ascent / 2 - size / 2 + (paint.fontMetricsInt.descent) / 2
        icon.setTint(paint.color)
        icon.setBounds(x.toInt(), iconTop, x.toInt() + size, iconTop + size)
        icon.draw(canvas)
    }
}
