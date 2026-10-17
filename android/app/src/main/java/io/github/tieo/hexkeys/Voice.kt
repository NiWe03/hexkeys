package io.github.tieo.hexkeys

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** What a dictation tells the keyboard while it runs; all of it arrives on the main thread. */
class Dictation(
    /** The loudness of the last tenth of a second, 0 to 1. */
    val level: (Float) -> Unit,
    /** Text recognised from one stretch of speech, in the order it was said. */
    val text: (String) -> Unit,
    /** Listening stopped, or a stretch was handed on or finished recognising. */
    val changed: () -> Unit,
)

/**
 * Speech input: the model downloaded on request, recording from the
 * microphone at 16 kHz, and handing the recording to the model. A dictation is cut into stretches at pauses; each is recognised
 * while the next is still being spoken and its text put in at once, so a
 * long dictation needs no more than its last stretch recognised after it
 * ends, and nothing said is lost to a length limit. Recording and
 * recognition run off the main thread.
 */
class Voice(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Recognition runs below the keyboard's own priority, as do the threads
     * the model starts from it, which inherit it: at full speed on every fast
     * core it held up the keyboard's drawing for over a second at a time.
     */
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }
    }

    private var recognizer: Recognizer? = null

    /** Whether it is listening now. */
    @Volatile
    var listening = false
        private set

    /** Stretches handed on and not recognised yet. */
    var pending = 0
        private set

    /** When listening started, as [android.os.SystemClock.uptimeMillis]. */
    var startedAt = 0L
        private set

    /**
     * Starts listening; false when there is no model or no permission to
     * use the microphone, which settings take care of.
     */
    @SuppressLint("MissingPermission")
    fun start(dictation: Dictation): Boolean {
        if (listening) return false
        if (!ready(context)) return false
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, RATE),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        listening = true
        startedAt = android.os.SystemClock.uptimeMillis()
        // Loaded while the first words are spoken rather than after them.
        worker.execute { runCatching { load() } }
        record.startRecording()
        Thread {
            try {
                listen({ chunk -> record.read(chunk, 0, chunk.size) }, dictation)
            } finally {
                record.stop()
                record.release()
            }
        }.start()
        return true
    }

    /**
     * Dictates from recorded 16 kHz audio instead of the microphone, at the
     * pace it was spoken, for testing where there is no microphone.
     */
    fun dictate(audio: ShortArray, dictation: Dictation) {
        if (listening) return
        listening = true
        startedAt = android.os.SystemClock.uptimeMillis()
        var position = 0
        Thread {
            listen({ chunk ->
                Thread.sleep(CHUNK_MS.toLong())
                val count = minOf(chunk.size, audio.size - position)
                if (count <= 0) {
                    listening = false
                    return@listen 0
                }
                audio.copyInto(chunk, 0, position, position + count)
                position += count
                count
            }, dictation)
        }.start()
    }

    /** Stops listening; what was said last is still recognised and put in. */
    fun stop() {
        listening = false
    }

    /**
     * Records until stopped or [MAX_MINUTES] are up, cutting a stretch off
     * after a pause once some speech came before it, or at the quietest
     * moment of its last seconds when it grows to [MAX_STRETCH_MS].
     * Stretches without speech are dropped: recognising silence costs time
     * and can make up words.
     */
    private fun listen(read: (ShortArray) -> Int, dictation: Dictation) {
        val chunk = ShortArray(CHUNK)
        val stretch = FloatArray(RATE * MAX_STRETCH_MS / 1000)
        var count = 0
        val loudness = ArrayList<Float>()
        var spoken = false
        var quietFor = 0
        var floor = 1f
        val until = startedAt + MAX_MINUTES * 60_000L
        fun handOn(end: Int) {
            if (spoken) recognise(stretch.copyOf(end), dictation)
            stretch.copyInto(stretch, 0, end, count)
            count -= end
            loudness.subList(0, minOf(loudness.size, end / CHUNK)).clear()
            spoken = loudness.any { it > threshold(floor) }
            quietFor = 0
        }
        try {
            while (listening && android.os.SystemClock.uptimeMillis() < until) {
                val read = read(chunk)
                if (read <= 0) continue
                var sum = 0f
                for (i in 0 until read) {
                    val value = chunk[i] / 32768f
                    stretch[count++] = value
                    sum += value * value
                }
                val rms = kotlin.math.sqrt(sum / read)
                loudness += rms
                // The quietest moment so far is the room's own noise.
                floor = minOf(floor, rms.coerceAtLeast(0.001f))
                if (rms > threshold(floor)) {
                    spoken = true
                    quietFor = 0
                } else {
                    quietFor += CHUNK_MS
                }
                main.post { dictation.level(rms) }
                if (spoken && quietFor >= PAUSE_MS && count >= RATE * MIN_STRETCH_MS / 1000) {
                    handOn(count)
                } else if (count + CHUNK > stretch.size) {
                    // Too long without a pause: cut at the quietest tenth of a second of the last few.
                    val recent = loudness.size - CUT_SEARCH_CHUNKS
                    val quietest = (maxOf(1, recent) until loudness.size).minBy { loudness[it] }
                    handOn(quietest * CHUNK)
                }
            }
            handOn(count)
        } finally {
            listening = false
            main.post { dictation.changed() }
        }
    }

    private fun threshold(floor: Float) = maxOf(SPEECH_LEVEL, floor * 3f)

    /** Recognises one stretch after those before it and hands on its text. */
    private fun recognise(audio: FloatArray, dictation: Dictation) {
        main.post {
            pending += 1
            dictation.changed()
        }
        worker.execute {
            val text = runCatching {
                // A new line in the text would send the message in some apps.
                load()?.transcribe(audio)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            }.getOrDefault("")
            main.post {
                pending -= 1
                if (text.isNotBlank()) dictation.text(text.trim())
                dictation.changed()
            }
        }
    }

    /** Loads the model unless it is loaded already; null when it is not downloaded. Runs on [worker]. */
    private fun load(): Recognizer? {
        if (!enabled(context)) return null
        return recognizer ?: VoiceModels.load(folder(context), THREADS).also { recognizer = it }
    }

    fun release() {
        listening = false
        worker.execute { recognizer?.release() }
        worker.shutdown()
    }

    companion object {
        private const val RATE = 16_000

        /** Audio is read a tenth of a second at a time. */
        private const val CHUNK_MS = 100
        private const val CHUNK = RATE * CHUNK_MS / 1000

        /** A pause this long after speech ends a stretch: the breath between sentences. */
        private const val PAUSE_MS = 500

        /** A stretch is at least this long, so a short breath does not split a sentence. */
        private const val MIN_STRETCH_MS = 4000

        /**
         * A stretch is at most this long, so speech without pauses still turns
         * into text every few seconds rather than once at the end.
         */
        private const val MAX_STRETCH_MS = 12_000

        /** Where a stretch too long for a pause is cut: its quietest tenth of a second within the last four seconds. */
        private const val CUT_SEARCH_CHUNKS = 40

        /** Loudness below which nothing counts as speech, however quiet the room. */
        private const val SPEECH_LEVEL = 0.01f

        /** The longest dictation. */
        private const val MAX_MINUTES = 10

        /** Preference holding the model's id while speech input is on. */
        const val MODEL = "voice_model"

        /** Threads recognition runs on: the phone's fast cores. */
        const val THREADS = 4

        val SPEECH_MODEL = VoiceModels.PARAKEET

        /** Whether speech input is on: switched on and its model downloaded. */
        fun enabled(context: Context) =
            Dictionaries.preferences(context).getString(MODEL, null) == SPEECH_MODEL.id && downloaded(context)

        /** Where the model's files are. */
        fun folder(context: Context) = File(File(context.filesDir, "voice"), SPEECH_MODEL.id)

        /** Deletes whatever else lies in the voice folder: models offered before, and their timings. */
        fun cleanUp(context: Context) {
            File(context.filesDir, "voice").listFiles()?.filter { it.name != SPEECH_MODEL.id }?.forEach { it.deleteRecursively() }
            val preferences = Dictionaries.preferences(context)
            val stale = preferences.all.keys.filter { it.startsWith("voice_timing_") }
            if (stale.isNotEmpty()) preferences.edit().apply { stale.forEach(::remove) }.apply()
        }

        /** Switches speech input off and deletes the model. */
        fun delete(context: Context) {
            Dictionaries.preferences(context).edit().remove(MODEL).apply()
            folder(context).deleteRecursively()
        }

        fun downloaded(context: Context) =
            SPEECH_MODEL.files.all { File(folder(context), it.name).length() == it.bytes }

        fun permitted(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Whether speaking can start: speech input is on and the microphone allowed. */
        fun ready(context: Context) = enabled(context) && permitted(context)

        /**
         * Downloads a model's files, reporting progress from 0 to 1, each
         * checked against the SHA-256 it had at the pinned commit; throws
         * when one fails. Files already in place are kept.
         */
        fun download(context: Context, progress: (Float) -> Unit) {
            val model = SPEECH_MODEL
            val folder = folder(context).apply { mkdirs() }
            val total = model.files.sumOf { it.bytes }.toFloat()
            var done = 0L
            for (file in model.files) {
                val target = File(folder, file.name)
                if (target.length() == file.bytes) {
                    done += file.bytes
                    continue
                }
                val partial = File(target.path + ".part")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val connection = URL(model.url(file)).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = true
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw java.io.IOException("HTTP ${connection.responseCode}")
                    }
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                done += read
                                progress(done / total)
                            }
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (file.sha256.isNotEmpty() && hash != file.sha256) throw java.io.IOException("${file.name} is not the file pinned")
                    if (!partial.renameTo(target)) throw java.io.IOException("cannot store ${file.name}")
                } finally {
                    connection.disconnect()
                    partial.delete()
                }
            }
        }
    }
}
