package io.github.tieo.hexkeys

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** A text copied, when, and whether it is pinned to stay. */
data class Clip(val text: String, val time: Long, val pinned: Boolean)

/**
 * The texts copied while the keyboard ran, newest first, as other keyboards
 * keep them: pinned ones stay, the others go after [KEEP_MS], and texts the
 * app copying them marks sensitive, such as passwords, are never kept.
 */
class Clips(private val context: Context, private val changed: () -> Unit = {}) {

    private val preferences get() = Dictionaries.preferences(context)
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    /** When the newest clip was copied, for the strip to offer it while it is fresh. */
    var copiedAt = 0L
        private set

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { take() }

    fun listen() = clipboard.addPrimaryClipChangedListener(listener)

    fun stop() = clipboard.removePrimaryClipChangedListener(listener)

    /** Keeps what is on the clipboard now, unless it is sensitive or empty. */
    private fun take() {
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val sensitive = Build.VERSION.SDK_INT >= 33 &&
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        if (sensitive) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        val pinned = all().any { it.text == text && it.pinned }
        save(listOf(Clip(text, now, pinned)) + all().filter { it.text != text })
        copiedAt = now
        changed()
    }

    /** The clips kept, newest first, the expired left out. */
    fun all(): List<Clip> {
        val now = System.currentTimeMillis()
        val array = runCatching { JSONArray(preferences.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getJSONObject(it) }
            .map { Clip(it.getString("text"), it.getLong("time"), it.optBoolean("pinned")) }
            .filter { it.pinned || now - it.time < KEEP_MS }
    }

    /** The newest clip while it was copied less than [FRESH_MS] ago and not offered away yet. */
    fun fresh(): Clip? = all().firstOrNull()?.takeIf { System.currentTimeMillis() - copiedAt < FRESH_MS && it.time == copiedAt }

    /** The fresh clip is no longer offered: pasted, or typed past. */
    fun spend() {
        copiedAt = 0L
    }

    fun pin(clip: Clip, pinned: Boolean) = save(all().map { if (it.text == clip.text) it.copy(pinned = pinned) else it })

    fun remove(clip: Clip) = save(all().filter { it.text != clip.text })

    private fun save(clips: List<Clip>) {
        val unpinned = clips.filter { !it.pinned }.take(MAX_UNPINNED).toSet()
        val array = JSONArray()
        for (clip in clips.filter { it.pinned || it in unpinned }) {
            array.put(JSONObject().put("text", clip.text).put("time", clip.time).put("pinned", clip.pinned))
        }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "clips"

        /** How long a clip that is not pinned is kept: an hour, as on other keyboards. */
        const val KEEP_MS = 60 * 60 * 1000L

        /** How long after copying the strip offers the newest clip. */
        const val FRESH_MS = 60 * 1000L

        const val MAX_UNPINNED = 30
    }
}
