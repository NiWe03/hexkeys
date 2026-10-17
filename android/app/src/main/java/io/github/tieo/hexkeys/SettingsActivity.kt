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

    private lateinit var setup: View
    private lateinit var setupButton: Button
    private lateinit var languages: TextView
    private val ui by lazy { Ui(this) }

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
            ui.toggle("Autocorrect", Dictionaries.AUTOCORRECT),
            learningRow(),
        ))

        column.addView(ui.heading("Gestures"))
        column.addView(ui.card(
            ui.label("Delete by sliding in from"),
            ui.segmented(
                listOf("right" to "Right edge", "left" to "Left edge"),
                if (ui.preferences.getBoolean(KeyboardView.DELETE_FROM_LEFT, false)) "left" else "right",
            ) { id -> ui.preferences.edit().putBoolean(KeyboardView.DELETE_FROM_LEFT, id == "left").apply() },
            ui.label("Capital letter"),
            ui.segmented(
                KeyboardView.CAPITALS.zip(listOf("Flick up", "Up and back", "Flick down", "Hold")),
                ui.preferences.getString(KeyboardView.CAPITAL, null) ?: KeyboardView.CAPITALS.first(),
                columns = 2,
            ) { id -> ui.preferences.edit().putString(KeyboardView.CAPITAL, id).apply() },
        ))

        column.addView(ui.heading("Look"))
        column.addView(ui.card(
            ui.label("Layout"),
            ui.segmented(
                listOf("hexkeys" to "Hexkeys", KeyboardView.SPACE_MIDDLE to "the reference layout test"),
                ui.preferences.getString(KeyboardView.LAYOUT, null) ?: "hexkeys",
            ) { id -> ui.preferences.edit().putString(KeyboardView.LAYOUT, id).apply() },
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

        setContentView(ui.page(column))
        title = "Hexkeys"
    }

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
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard picker is a dialog over this window, not a new screen.
        if (hasFocus) onResume()
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

    fun toggle(label: String, key: String) = Switch(activity).apply {
        text = label
        setTextColor(this@Ui.text)
        textSize = 17f
        minHeight = dp(52)
        isChecked = preferences.getBoolean(key, false)
        setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean(key, checked).apply() }
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
        options: List<Pair<String, String>>,
        chosen: String,
        columns: Int = options.size,
        choose: (String) -> Unit,
    ): View {
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val buttons = mutableListOf<Pair<String, TextView>>()
        fun paint(current: String) {
            for ((id, button) in buttons) {
                val on = id == current
                button.background = rounded(if (on) accent else idleColor, 12)
                button.setTextColor(if (on) onAccent else 0xFFE8E8EC.toInt())
            }
        }
        for (start in options.indices step columns) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for ((id, name) in options.subList(start, minOf(start + columns, options.size))) {
                val button = TextView(activity).apply {
                    text = name
                    textSize = 15f
                    gravity = Gravity.CENTER
                    minHeight = dp(44)
                    setOnClickListener {
                        paint(id)
                        choose(id)
                    }
                }
                buttons += id to button
                row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
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
