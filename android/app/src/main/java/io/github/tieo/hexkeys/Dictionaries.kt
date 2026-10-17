package io.github.tieo.hexkeys

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One downloadable dictionary from the catalog in the assets. */
data class Language(val name: String, val locale: String, val file: String, val license: String)

/**
 * Which dictionaries the keyboard suggests from and where they live: the
 * built-in English one in the assets, the others downloaded into the app's
 * files from the aosp-dictionaries repository, at the commit the catalog
 * was built from (tools/dictionaries.py).
 */
object Dictionaries {
    const val BUILT_IN = "main_en_us.dict"
    private const val COMMIT = "ff0cd52be8d9530200fefe8b296c53d4f662091e"
    private const val BASE = "https://codeberg.org/Helium314/aosp-dictionaries/raw/commit/$COMMIT/dictionaries/"

    const val ENABLED = "dictionaries"
    /** Before there were modes: on meant correcting everything. */
    const val AUTOCORRECT = "autocorrect"

    /** What autocorrect changes: [AUTOCORRECT_MODES] off, only obvious slips, or every mistake. */
    const val AUTOCORRECT_MODE = "autocorrect_mode"
    val AUTOCORRECT_MODES = listOf("off", "obvious", "all")

    fun autocorrectMode(context: Context): String {
        val preferences = preferences(context)
        return preferences.getString(AUTOCORRECT_MODE, null)?.takeIf { it in AUTOCORRECT_MODES }
            ?: if (preferences.getBoolean(AUTOCORRECT, false)) "all" else "obvious"
    }
    const val LEARNING = "learning"

    /** Bumped when the learned words are deleted, so the keyboard drops them too. */
    const val LEARNED_RESET = "learned_reset"

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun catalog(context: Context): List<Language> =
        context.assets.open("dictionaries.tsv").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').let { Language(it[0], it[1], it[2], it[3]) } }

    fun enabled(context: Context): Set<String> =
        preferences(context).getStringSet(ENABLED, null) ?: setOf(BUILT_IN)

    /**
     * The languages to start with, as every keyboard does: English, which
     * is built in, and each of the phone's own languages the catalog has,
     * by its country variant where there is one.
     */
    fun deviceLanguages(context: Context): Set<String> {
        val catalog = catalog(context)
        val locales = android.content.res.Resources.getSystem().configuration.locales
        val chosen = mutableSetOf(BUILT_IN)
        for (i in 0 until locales.size()) {
            val locale = locales[i]
            val exact = "${locale.language}_${locale.country}".lowercase()
            val match = catalog.firstOrNull { it.locale.lowercase() == exact }
                ?: catalog.firstOrNull { it.locale.lowercase() == locale.language.lowercase() }
            if (match != null) chosen += match.file
        }
        return chosen
    }

    /** Whether languages have been chosen yet, or the phone's are still to be taken. */
    fun chosen(context: Context) = preferences(context).contains(ENABLED)

    fun setEnabled(context: Context, files: Set<String>) {
        preferences(context).edit().putStringSet(ENABLED, files).apply()
    }

    private fun downloaded(context: Context, file: String) = File(File(context.filesDir, "dictionaries"), file)

    fun isAvailable(context: Context, file: String) = file == BUILT_IN || downloaded(context, file).exists()

    /** The dictionary's bytes, or null when it has not been downloaded. */
    fun read(context: Context, file: String): ByteArray? = when {
        file == BUILT_IN -> context.assets.open(file).use { it.readBytes() }
        downloaded(context, file).exists() -> downloaded(context, file).readBytes()
        else -> null
    }

    /**
     * Downloads [file], reporting progress from 0 to 1; blocks, so it runs
     * off the main thread. The file only appears once complete.
     */
    fun download(context: Context, file: String, progress: (Float) -> Unit) {
        val target = downloaded(context, file)
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        val connection = URL(BASE + file).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
            if (!partial.renameTo(target)) throw java.io.IOException("cannot store $file")
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    private fun learnedFile(context: Context) = File(context.filesDir, "learned.tsv")

    /** Words learned from typing, with how often each was kept. */
    fun learned(context: Context): Map<String, Int> {
        val file = learnedFile(context)
        if (!file.exists()) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val (word, times) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            times.toIntOrNull()?.let { word to it }
        }.toMap()
    }

    fun saveLearned(context: Context, words: Map<String, Int>) {
        learnedFile(context).writeText(words.entries.joinToString("") { "${it.key}\t${it.value}\n" })
    }

    fun deleteLearned(context: Context) {
        learnedFile(context).delete()
        val preferences = preferences(context)
        preferences.edit().putInt(LEARNED_RESET, preferences.getInt(LEARNED_RESET, 0) + 1).apply()
    }

    fun delete(context: Context, file: String) {
        if (file != BUILT_IN) downloaded(context, file).delete()
    }
}
