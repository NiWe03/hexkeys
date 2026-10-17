package io.github.tieo.hexkeys

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build

/**
 * The keyboard's colours. Letter keys take the colour of the row their
 * letter has on an ordinary QWERTZ keyboard ([rows], top to bottom); every
 * other key takes [other]. Pressed and popup shades derive from these.
 */
data class Theme(
    val id: String,
    val name: String,
    val background: Int,
    val rows: List<Int>,
    val other: Int,
    val label: Int,
    val accent: Int,
) {
    /** A key's colour while pressed: moved a quarter of the way to the label colour. */
    fun pressed(color: Int) = blend(color, label, 0.25f)

    val popup get() = blend(other, label, 0.12f)

    companion object {
        const val KEY = "theme"

        private fun starters(context: Context): List<Theme> = listOfNotNull(
            Theme(
                "dusk", "Dusk", 0xFF1B1B1F.toInt(),
                listOf(0xFF2E3B5E.toInt(), 0xFF234845.toInt(), 0xFF4B2E4F.toInt()),
                0xFF3A3A42.toInt(), 0xFFE8E8EE.toInt(), 0xFF6C7BD8.toInt(),
            ),
            Theme(
                "paper", "Paper", 0xFFE4E4EA.toInt(),
                listOf(0xFFC7D7F6.toInt(), 0xFFC4E8D4.toInt(), 0xFFF4CFDA.toInt()),
                0xFFFBFBFD.toInt(), 0xFF1B1B1F.toInt(), 0xFF4A5BC8.toInt(),
            ),
            Theme(
                "ember", "Ember", 0xFF1E1A18.toInt(),
                listOf(0xFF5A3A22.toInt(), 0xFF4E2A2A.toInt(), 0xFF3F3A22.toInt()),
                0xFF3A3431.toInt(), 0xFFF3ECE6.toInt(), 0xFFE0894A.toInt(),
            ),
            deviceColors(context),
            Theme(
                "plain-dark", "Plain dark", 0xFF1B1B1F.toInt(),
                List(3) { 0xFF3A3A42.toInt() }, 0xFF3A3A42.toInt(), 0xFFE8E8EE.toInt(), 0xFF6C7BD8.toInt(),
            ),
            Theme(
                "plain-light", "Plain light", 0xFFE4E4EA.toInt(),
                List(3) { 0xFFFBFBFD.toInt() }, 0xFFFBFBFD.toInt(), 0xFF1B1B1F.toInt(), 0xFF4A5BC8.toInt(),
            ),
        )

        /**
         * The system's palette from the wallpaper (Android 12 and later): one
         * accent palette per row, dark or light with the system setting.
         */
        private fun deviceColors(context: Context): Theme? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            fun color(id: Int) = context.getColor(id)
            return if (night) {
                Theme(
                    "device", "Device colors", color(android.R.color.system_neutral1_900),
                    listOf(
                        color(android.R.color.system_accent1_800),
                        color(android.R.color.system_accent2_800),
                        color(android.R.color.system_accent3_800),
                    ),
                    color(android.R.color.system_neutral2_800), color(android.R.color.system_neutral1_50),
                    color(android.R.color.system_accent1_300),
                )
            } else {
                Theme(
                    "device", "Device colors", color(android.R.color.system_neutral1_100),
                    listOf(
                        color(android.R.color.system_accent1_100),
                        color(android.R.color.system_accent2_100),
                        color(android.R.color.system_accent3_100),
                    ),
                    color(android.R.color.system_neutral1_10), color(android.R.color.system_neutral1_900),
                    color(android.R.color.system_accent1_600),
                )
            }
        }

        fun all(context: Context): List<Theme> = starters(context)

        fun current(context: Context): Theme {
            val id = Dictionaries.preferences(context).getString(KEY, null)
            val themes = all(context)
            return themes.firstOrNull { it.id == id } ?: themes.first()
        }

        fun blend(from: Int, to: Int, amount: Float): Int = Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).toInt(),
        )
    }
}
