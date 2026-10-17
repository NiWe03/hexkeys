package io.github.tieo.hexkeys

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/** One file of a model, with the size and SHA-256 it had at the pinned commit. */
data class ModelFile(val name: String, val bytes: Long, val sha256: String)

/** A speech model to download: its files in a Hugging Face repository at a pinned commit. */
data class VoiceModel(
    val id: String,
    val repository: String,
    val commit: String,
    val files: List<ModelFile>,
) {
    val megabytes get() = (files.sumOf { it.bytes } / 1_000_000).toInt()
    fun url(file: ModelFile) = "https://huggingface.co/$repository/resolve/$commit/${file.name}"
}

/**
 * The speech model: NVIDIA's Parakeet TDT 0.6B v3, int8, run by sherpa-onnx.
 * It detects 25 European languages, English and German among them, writes
 * punctuation and capitals, and on the phone recognised a sentence of five
 * seconds in 1.6 s, where whisper base took 10.6 s.
 */
object VoiceModels {
    val PARAKEET = VoiceModel(
        "parakeet",
        "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "2bda32ec70b097a55adaa07d9a7173915b43cc78",
        listOf(
            ModelFile("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
            ModelFile("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
            ModelFile("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
            ModelFile("tokens.txt", 93_939, ""),
        ),
    )

    /** Loads the model from the folder its files are in. */
    fun load(folder: File, threads: Int): Recognizer {
        fun path(name: String) = File(folder, name).path
        val model = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = path("encoder.int8.onnx"),
                decoder = path("decoder.int8.onnx"),
                joiner = path("joiner.int8.onnx"),
            ),
            tokens = path("tokens.txt"),
            modelType = "nemo_transducer",
            numThreads = threads,
        )
        return Recognizer(OfflineRecognizer(null, OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80), modelConfig = model)))
    }
}

private const val RATE = 16_000

/** The loaded model, turning a stretch of 16 kHz audio into text in whatever language it hears. */
class Recognizer(private val recognizer: OfflineRecognizer) {
    fun transcribe(audio: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio, RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()
}
