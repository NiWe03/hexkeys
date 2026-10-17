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

/**
 * Speech input: the whisper models offered for download, recording from the
 * microphone at 16 kHz, and handing the recording to whisper.cpp in the
 * core. Recording and recognition run off the main thread; results come
 * back on it.
 */
class Voice(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var model = 0L
    private var loadedFile: String? = null
    private var recorder: AudioRecord? = null
    private var samples = FloatArray(0)
    private var count = 0

    /** Whether it is listening now. */
    var listening = false
        private set

    /** Whether recognition is under way. */
    var working = false
        private set

    /**
     * Starts listening; false when there is no model or no permission to
     * use the microphone, which settings take care of.
     */
    @SuppressLint("MissingPermission")
    fun start(level: (Float) -> Unit): Boolean {
        if (listening || working) return false
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
        recorder = record
        samples = FloatArray(RATE * MAX_SECONDS)
        count = 0
        listening = true
        record.startRecording()
        Thread {
            val chunk = ShortArray(RATE / 10)
            while (listening) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                var sum = 0f
                for (i in 0 until read) {
                    val value = chunk[i] / 32768f
                    if (count < samples.size) samples[count++] = value
                    sum += value * value
                }
                val rms = kotlin.math.sqrt(sum / read)
                main.post { level(rms) }
                // Whisper hears 30 seconds at a time; stop there.
                if (count >= samples.size) main.post { listening = false }
            }
        }.start()
        return true
    }

    /** Stops listening and recognises what was said; `done` gets the text, empty when nothing was made out. */
    fun stop(languages: String, done: (String) -> Unit) {
        if (!listening) return
        listening = false
        working = true
        val record = recorder
        recorder = null
        val audio = samples.copyOf(count)
        worker.execute {
            record?.stop()
            record?.release()
            val text = runCatching {
                val file = chosen(context)?.let { file(context, it) }
                if (file != null && file.path != loadedFile) {
                    if (model != 0L) Core.voiceFree(model)
                    model = Core.voiceLoad(file.path)
                    loadedFile = file.path
                }
                if (model == 0L || audio.size < RATE / 4) return@runCatching ""
                val started = System.currentTimeMillis()
                val text = Core.voiceTranscribe(model, audio, languages)
                chosen(context)?.let { measured(context, it, System.currentTimeMillis() - started, audio.size * 1000L / RATE) }
                text
            }.getOrDefault("")
            main.post {
                working = false
                done(text)
            }
        }
    }

    fun release() {
        listening = false
        recorder?.release()
        worker.execute { if (model != 0L) Core.voiceFree(model) }
        worker.shutdown()
    }

    companion object {
        private const val RATE = 16_000
        private const val MAX_SECONDS = 30

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
