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
    const val AUTOCORRECT = "autocorrect"
    const val LEARNING = "learning"

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun catalog(context: Context): List<Language> =
        context.assets.open("dictionaries.tsv").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').let { Language(it[0], it[1], it[2], it[3]) } }

    fun enabled(context: Context): Set<String> =
        preferences(context).getStringSet(ENABLED, null) ?: setOf(BUILT_IN)

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

    fun delete(context: Context, file: String) {
        if (file != BUILT_IN) downloaded(context, file).delete()
    }
}
