package io.github.tieo.hexkeys

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The keyboard's settings, which the launcher and the system's keyboard
 * settings both open: setting the keyboard up while it is not the active
 * one, autocorrect and learning, and the languages to suggest in.
 */
class SettingsActivity : Activity() {

    private val downloads = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var setup: LinearLayout
    private lateinit var enable: Button
    private lateinit var switch: Button

    private val density get() = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        enable = Button(this).apply {
            text = "Enable Hexkeys"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }
        switch = Button(this).apply {
            text = "Switch to Hexkeys"
            setOnClickListener { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        }
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(enable)
            addView(switch)
        }
        column.addView(setup)

        column.addView(heading("Typing"))
        column.addView(toggle("Autocorrect", Dictionaries.AUTOCORRECT))
        column.addView(toggle("Learn new words", Dictionaries.LEARNING))

        column.addView(heading("Languages"))
        val filter = EditText(this).apply {
            hint = "Search"
            setSingleLine()
        }
        column.addView(filter)
        // The languages in use first, then the rest alphabetically.
        val enabled = Dictionaries.enabled(this)
        val rows = Dictionaries.catalog(this)
            .sortedBy { it.file !in enabled }
            .map { language -> language to languageRow(language) }
        rows.forEach { column.addView(it.second) }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                for ((language, row) in rows) {
                    row.visibility = if (language.name.contains(query, ignoreCase = true)) View.VISIBLE else View.GONE
                }
            }
        })

        val scroll = ScrollView(this).apply { addView(column) }
        // Edge to edge: keep the content out from under the system bars.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        title = "Hexkeys"
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system settings: show only the step still missing.
        val manager = getSystemService(InputMethodManager::class.java)
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        enable.visibility = if (enabled) View.GONE else View.VISIBLE
        switch.visibility = if (enabled && !current) View.VISIBLE else View.GONE
        setup.visibility = if (enabled && current) View.GONE else View.VISIBLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard picker is a dialog over this window, not a new screen.
        if (hasFocus) onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloads.shutdownNow()
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFF8AB4F8.toInt())
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun toggle(label: String, key: String) = Switch(this).apply {
        text = label
        textSize = 17f
        minHeight = dp(48)
        val preferences = Dictionaries.preferences(context)
        isChecked = preferences.getBoolean(key, false)
        setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean(key, checked).apply() }
    }

    /**
     * A language with a check box: checking downloads its dictionary, with
     * the progress in the row, unchecking removes it. The last language
     * cannot be unchecked, so there is always one to suggest in.
     */
    private fun languageRow(language: Language): View {
        val context: Context = this
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        val box = CheckBox(this).apply {
            text = language.name
            textSize = 17f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isChecked = language.file in Dictionaries.enabled(context) && Dictionaries.isAvailable(context, language.file)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
            max = 1000
            visibility = View.GONE
        }
        row.addView(box)
        row.addView(progress)
        box.setOnCheckedChangeListener { view, checked ->
            val enabled = Dictionaries.enabled(context)
            if (!checked) {
                if (enabled.size <= 1) {
                    view.isChecked = true
                    return@setOnCheckedChangeListener
                }
                Dictionaries.setEnabled(context, enabled - language.file)
                Dictionaries.delete(context, language.file)
                return@setOnCheckedChangeListener
            }
            if (Dictionaries.isAvailable(context, language.file)) {
                Dictionaries.setEnabled(context, enabled + language.file)
                return@setOnCheckedChangeListener
            }
            box.isEnabled = false
            progress.progress = 0
            progress.visibility = View.VISIBLE
            downloads.execute {
                val ok = runCatching {
                    Dictionaries.download(context, language.file) { done ->
                        main.post { progress.progress = (done * 1000).toInt() }
                    }
                }.isSuccess
                main.post {
                    progress.visibility = View.GONE
                    box.isEnabled = true
                    if (ok) Dictionaries.setEnabled(context, Dictionaries.enabled(context) + language.file)
                    else box.isChecked = false
                }
            }
        }
        return row
    }
}
