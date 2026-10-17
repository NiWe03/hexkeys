package io.github.tieo.hexkeys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs a 16 kHz mono 16-bit WAV file through speech recognition with the
 * chosen model, or the one named by `model` (an id of [VoiceModels.ALL]), and logs
 * the text and the time it took, for testing on an emulator, which has no
 * microphone to speak into:
 * `adb shell am broadcast -n io.github.tieo.hexkeys/.VoiceTestReceiver --es wav /data/local/tmp/test.wav --es languages en,de --es model tiny`
 * With `--ez dictate true` the file goes through dictation instead, as if
 * spoken into the microphone, and each stretch it is cut into is logged.
 */
class VoiceTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val wav = File(intent.getStringExtra("wav") ?: return)
        val languages = intent.getStringExtra("languages") ?: "en"
        val bytes = wav.readBytes()
        val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        if (intent.getBooleanExtra("dictate", false)) {
            val audio = ShortArray(pcm.remaining()).also { pcm.get(it) }
            val voice = Voice(context.applicationContext)
            // Kept alive until done: a receiver's process is frozen soon after it returns.
            val pending = goAsync()
            val started = System.currentTimeMillis()
            voice.dictate(audio, languages, Dictation(
                level = {},
                text = { Log.i("hexkeys", "voice test: stretch at ${System.currentTimeMillis() - started} ms: $it") },
                changed = {
                    if (!voice.listening && voice.pending == 0) {
                        Log.i("hexkeys", "voice test: done at ${System.currentTimeMillis() - started} ms for ${audio.size / 16} ms of audio")
                        pending.finish()
                    }
                },
            ))
            return
        }
        val pending = goAsync()
        Thread {
            val audio = FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
            val model = Voice.MODELS.firstOrNull { it.id == intent.getStringExtra("model") } ?: Voice.chosen(context)
            val started = System.currentTimeMillis()
            val recognizer = model?.let { VoiceModels.load(it, Voice.folder(context, it), Voice.THREADS) }
            val loaded = System.currentTimeMillis()
            val text = recognizer?.transcribe(audio, languages.split(',')) ?: "(no model)"
            val done = System.currentTimeMillis()
            recognizer?.release()
            Log.i("hexkeys", "voice test: load ${loaded - started} ms, recognise ${done - loaded} ms for ${audio.size / 16} ms of audio: $text")
            pending.finish()
        }.start()
    }
}
