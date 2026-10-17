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
 * chosen model, or the one named by `model` (tiny, base, small), and logs the text and the time it took, for testing on an
 * emulator, which has no microphone to speak into:
 * `adb shell am broadcast -n io.github.tieo.hexkeys/.VoiceTestReceiver --es wav /data/local/tmp/test.wav --es languages en,de --es model tiny`
 */
class VoiceTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val wav = File(intent.getStringExtra("wav") ?: return)
        val languages = intent.getStringExtra("languages") ?: "en"
        val pending = goAsync()
        Thread {
            val bytes = wav.readBytes()
            val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val audio = FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
            val model = (Voice.MODELS.firstOrNull { it.id == intent.getStringExtra("model") } ?: Voice.chosen(context))
                ?.let { Voice.file(context, it) }
            val started = System.currentTimeMillis()
            val handle = model?.let { Core.voiceLoad(it.path) } ?: 0L
            val loaded = System.currentTimeMillis()
            val text = if (handle != 0L) Core.voiceTranscribe(handle, audio, languages) else "(no model)"
            val done = System.currentTimeMillis()
            if (handle != 0L) Core.voiceFree(handle)
            Log.i("hexkeys", "voice test: load ${loaded - started} ms, recognise ${done - loaded} ms for ${audio.size / 16} ms of audio: $text")
            pending.finish()
        }.start()
    }
}
