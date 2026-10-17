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

/** A speech model the user can download, by size. */
data class VoiceModel(val id: String, val name: String, val file: String, val megabytes: Int)

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
 * Speech input: the whisper models offered for download, recording from the
 * microphone at 16 kHz, and handing the recording to whisper.cpp in the
 * core. A dictation is cut into stretches at pauses; each is recognised
 * while the next is still being spoken and its text put in at once, so a
 * long dictation needs no more than its last stretch recognised after it
 * ends, and nothing said is lost to a length limit. Recording and
 * recognition run off the main thread.
 */
class Voice(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var model = 0L
    private var loadedFile: String? = null

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
    fun start(languages: String, dictation: Dictation): Boolean {
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
        record.startRecording()
        Thread {
            try {
                listen({ chunk -> record.read(chunk, 0, chunk.size) }, languages, dictation)
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
    fun dictate(audio: ShortArray, languages: String, dictation: Dictation) {
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
            }, languages, dictation)
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
     * Stretches without speech are dropped: whisper makes up words for
     * silence.
     */
    private fun listen(read: (ShortArray) -> Int, languages: String, dictation: Dictation) {
        val chunk = ShortArray(CHUNK)
        val stretch = FloatArray(RATE * MAX_STRETCH_MS / 1000)
        var count = 0
        val loudness = ArrayList<Float>()
        var spoken = false
        var quietFor = 0
        var floor = 1f
        val until = startedAt + MAX_MINUTES * 60_000L
        fun handOn(end: Int) {
            if (spoken) recognise(stretch.copyOf(end), languages, dictation)
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
    private fun recognise(audio: FloatArray, languages: String, dictation: Dictation) {
        main.post {
            pending += 1
            dictation.changed()
        }
        worker.execute {
            val text = runCatching {
                val chosen = chosen(context) ?: return@runCatching ""
                val file = file(context, chosen)
                if (file.path != loadedFile) {
                    if (model != 0L) Core.voiceFree(model)
                    model = Core.voiceLoad(file.path)
                    loadedFile = file.path
                }
                if (model == 0L) return@runCatching ""
                val started = System.currentTimeMillis()
                val text = Core.voiceTranscribe(model, audio, languages)
                measured(context, chosen, System.currentTimeMillis() - started, audio.size * 1000L / RATE)
                text
            }.getOrDefault("")
            main.post {
                pending -= 1
                if (text.isNotBlank()) dictation.text(text.trim())
                dictation.changed()
            }
        }
    }

    fun release() {
        listening = false
        worker.execute { if (model != 0L) Core.voiceFree(model) }
        worker.shutdown()
    }

    companion object {
        private const val RATE = 16_000

        /** Audio is read a tenth of a second at a time. */
        private const val CHUNK_MS = 100
        private const val CHUNK = RATE * CHUNK_MS / 1000

        /** A pause this long after speech ends a stretch. */
        private const val PAUSE_MS = 700

        /** A stretch is at least this long, so a short breath does not split a sentence. */
        private const val MIN_STRETCH_MS = 2000

        /** A stretch is at most this long; whisper hears 30 seconds at a time. */
        private const val MAX_STRETCH_MS = 28_000

        /** Where a stretch too long for a pause is cut: its quietest tenth of a second within the last four seconds. */
        private const val CUT_SEARCH_CHUNKS = 40

        /** Loudness below which nothing counts as speech, however quiet the room. */
        private const val SPEECH_LEVEL = 0.01f

        /** The longest dictation. */
        private const val MAX_MINUTES = 10

        /** Preference naming the chosen model's id. */
        const val MODEL = "voice_model"

        /** whisper.cpp's quantised multilingual models, at a pinned commit of their repository. */
        private const val COMMIT = "5359861c739e955e79d9a303bcbc70fb988958b1"
        private const val BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$COMMIT/"

        /** Preference prefix for a model's time for a sentence, by its id. */
        private const val TIMING = "voice_timing_"

        /** The length of sentence times are given for: a sentence of about a dozen words. */
        const val SENTENCE_MS = 5000L

        /**
         * How long the models take compared with each other, from timing
         * all three on the same recordings with the same build.
         */
        private val RELATIVE_TIME = mapOf("tiny" to 10L, "base" to 32L, "small" to 133L)

        val MODELS = listOf(
            VoiceModel("tiny", "Fast", "ggml-tiny-q5_1.bin", 32),
            VoiceModel("base", "Balanced", "ggml-base-q5_1.bin", 60),
            VoiceModel("small", "Accurate", "ggml-small-q5_1.bin", 190),
        )

        fun chosen(context: Context): VoiceModel? {
            val id = Dictionaries.preferences(context).getString(MODEL, null)
            return MODELS.firstOrNull { it.id == id }?.takeIf { file(context, it).exists() }
        }

        fun file(context: Context, model: VoiceModel) = File(File(context.filesDir, "voice"), model.file)

        fun permitted(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Whether speaking can start: a model is downloaded and the microphone allowed. */
        fun ready(context: Context) = chosen(context) != null && permitted(context)

        /** Downloads a model, reporting progress from 0 to 1; throws when it fails. */
        fun download(context: Context, model: VoiceModel, progress: (Float) -> Unit) {
            val target = file(context, model)
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            val connection = URL(BASE + model.file).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw java.io.IOException("HTTP ${connection.responseCode}")
                }
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
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
                if (!partial.renameTo(target)) throw java.io.IOException("cannot store ${model.file}")
            } finally {
                connection.disconnect()
                partial.delete()
            }
        }

        /**
         * How long the model takes on this phone for a sentence of
         * [SENTENCE_MS], in ms, measured; null before it has been used.
         */
        fun timing(context: Context, model: VoiceModel): Long? =
            Dictionaries.preferences(context).getLong(TIMING + model.id, -1).takeIf { it > 0 }

        /**
         * How long the model takes for a sentence: measured, or else worked
         * out from a model measured on this phone by how the models compare
         * ([RELATIVE_TIME]); the flag tells which. Null when none is measured.
         */
        fun expectedTiming(context: Context, model: VoiceModel): Pair<Long, Boolean>? {
            timing(context, model)?.let { return it to true }
            val other = MODELS.firstOrNull { timing(context, it) != null } ?: return null
            return timing(context, other)!! * RELATIVE_TIME.getValue(model.id) / RELATIVE_TIME.getValue(other.id) to false
        }

        /** Counts one recognition of `audioMs` that took `ms`, scaled to a sentence and averaged with the earlier ones. */
        fun measured(context: Context, model: VoiceModel, ms: Long, audioMs: Long) {
            if (audioMs <= 0) return
            val sentence = ms * SENTENCE_MS / audioMs
            val before = timing(context, model)
            val average = if (before == null) sentence else (before * 2 + sentence) / 3
            Dictionaries.preferences(context).edit().putLong(TIMING + model.id, average).apply()
        }

        /** Times the model on the sentence in the app's assets, so its time shows before it is first spoken to. */
        fun measure(context: Context, model: VoiceModel) {
            val bytes = context.assets.open("sentence.wav").use { it.readBytes() }
            val pcm = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val audio = FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
            val handle = Core.voiceLoad(file(context, model).path)
            if (handle == 0L) return
            try {
                val started = System.currentTimeMillis()
                Core.voiceTranscribe(handle, audio, "en,de")
                measured(context, model, System.currentTimeMillis() - started, audio.size * 1000L / RATE)
            } finally {
                Core.voiceFree(handle)
            }
        }

        /** Removes every model but `keep`. */
        fun deleteOthers(context: Context, keep: VoiceModel?) {
            for (model in MODELS) if (model != keep) file(context, model).delete()
        }
    }
}
