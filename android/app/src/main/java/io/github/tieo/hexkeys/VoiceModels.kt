package io.github.tieo.hexkeys

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/** What runs a speech model: whisper.cpp in the core, or one of sherpa-onnx's model kinds. */
enum class Engine { WHISPER, PARAKEET, CANARY, KROKO, MOONSHINE }

/** One file of a model, with the size and SHA-256 it had at the pinned commit. */
data class ModelFile(val name: String, val bytes: Long, val sha256: String)

/**
 * A speech model to download: its files in a Hugging Face repository at a
 * pinned commit, and the languages it understands (null for whatever
 * whisper or Parakeet detect).
 */
data class VoiceModel(
    val id: String,
    val name: String,
    val engine: Engine,
    val repository: String,
    val commit: String,
    val files: List<ModelFile>,
    val languages: List<String>? = null,
) {
    val megabytes get() = (files.sumOf { it.bytes } / 1_000_000).toInt()
    fun url(file: ModelFile) = "https://huggingface.co/$repository/resolve/$commit/${file.name}"
}

/** A loaded model turning a stretch of 16 kHz audio into text. */
interface Recognizer {
    /** The text in `audio`, in one of `languages` (ISO codes, most used first). */
    fun transcribe(audio: FloatArray, languages: List<String>): String
    fun release()
}

/** Every model offered, with the one used when none has been chosen. */
object VoiceModels {
    private const val WHISPER = "ggerganov/whisper.cpp"
    private const val WHISPER_COMMIT = "5359861c739e955e79d9a303bcbc70fb988958b1"

    val ALL = listOf(
        VoiceModel(
            "tiny", "Whisper tiny", Engine.WHISPER, WHISPER, WHISPER_COMMIT,
            listOf(ModelFile("ggml-tiny-q5_1.bin", 32_152_673, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7")),
        ),
        VoiceModel(
            "base", "Whisper base", Engine.WHISPER, WHISPER, WHISPER_COMMIT,
            listOf(ModelFile("ggml-base-q5_1.bin", 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898")),
        ),
        VoiceModel(
            "small", "Whisper small", Engine.WHISPER, WHISPER, WHISPER_COMMIT,
            listOf(ModelFile("ggml-small-q5_1.bin", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb")),
        ),
        VoiceModel(
            "parakeet", "Parakeet", Engine.PARAKEET,
            "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", "2bda32ec70b097a55adaa07d9a7173915b43cc78",
            listOf(
                ModelFile("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                ModelFile("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                ModelFile("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                ModelFile("tokens.txt", 93_939, ""),
            ),
        ),
        VoiceModel(
            "canary", "Canary", Engine.CANARY,
            "csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8", "9077164e0d3dd1d5353743e89ceaa1d3a770838c",
            listOf(
                ModelFile("encoder.int8.onnx", 132_678_643, "7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f"),
                ModelFile("decoder.int8.onnx", 74_437_848, "e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2"),
                ModelFile("tokens.txt", 53_555, ""),
            ),
            languages = listOf("en", "de", "es", "fr"),
        ),
        VoiceModel(
            "kroko-de", "Kroko DE", Engine.KROKO,
            "csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06", "887db3d083240198c2d2b99fb66cfcfe6948ced8",
            listOf(
                ModelFile("encoder.onnx", 70_091_557, "6e83993d6967ec7a3498b055b7e85ace85b5d64d1b1e8773cb29a43a11f5edb5"),
                ModelFile("decoder.onnx", 617_489, "94a29592b403c53fa2231b478637da1ab4abcef7f5e46e432098416a4a3ed562"),
                ModelFile("joiner.onnx", 336_817, "28356bff070aea51ab1d725a3278e81d19f9300f860d3248a7014292264df15a"),
                ModelFile("tokens.txt", 5_606, ""),
            ),
            languages = listOf("de"),
        ),
        VoiceModel(
            "kroko-en", "Kroko EN", Engine.KROKO,
            "csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06", "572aaf4e2e0c603c3fc2a574d096e755a178faa1",
            listOf(
                ModelFile("encoder.onnx", 70_092_599, "d4881c57449d581e0770fd53fa66c2fdc6cd167d92ece7c715e603defc96d9d4"),
                ModelFile("decoder.onnx", 617_488, "455ba38466fce8d5a57e7db68a323b684079ca4d9e1dd93a740d9b2429aae3b1"),
                ModelFile("joiner.onnx", 336_817, "d406f616736350e2a7df3e39398b78eb2fc1a2ca6973a19d3853fa3227e25b52"),
                ModelFile("tokens.txt", 6_310, ""),
            ),
            languages = listOf("en"),
        ),
        VoiceModel(
            "moonshine", "Moonshine EN", Engine.MOONSHINE,
            "csukuangfj2/sherpa-onnx-moonshine-base-en-quantized-2026-02-27", "8f4d6c58c03d40bcea40043bb7120a878f2bbef6",
            listOf(
                ModelFile("encoder_model.ort", 31_326_816, "7c66495948d0d08ec1af454cd4b5514862ae6511e94712a60e6d83eaec8dc8cf"),
                ModelFile("decoder_model_merged.ort", 109_424_400, "d9d7b333af34bc552580576ddcf248a1c6c839e0d3b43b09afb9376ed009899d"),
                ModelFile("tokens.txt", 549_350, ""),
            ),
            languages = listOf("en"),
        ),
    )

    /**
     * The model the microphone downloads when none is chosen: Parakeet
     * detects 25 European languages, English and German among them, writes
     * punctuation and capitals, and is reported as the fastest of its
     * accuracy on phones.
     */
    val DEFAULT = ALL.first { it.id == "parakeet" }

    /** Loads `model` from the folder its files are in. */
    fun load(model: VoiceModel, folder: File, threads: Int): Recognizer {
        fun path(name: String) = File(folder, name).path
        return when (model.engine) {
            Engine.WHISPER -> WhisperRecognizer(path(model.files[0].name))
            Engine.PARAKEET -> SherpaOffline(
                OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = path("encoder.int8.onnx"),
                        decoder = path("decoder.int8.onnx"),
                        joiner = path("joiner.int8.onnx"),
                    ),
                    tokens = path("tokens.txt"),
                    modelType = "nemo_transducer",
                    numThreads = threads,
                ),
            )
            Engine.CANARY -> SherpaOffline(
                OfflineModelConfig(
                    canary = OfflineCanaryModelConfig(
                        encoder = path("encoder.int8.onnx"),
                        decoder = path("decoder.int8.onnx"),
                    ),
                    tokens = path("tokens.txt"),
                    numThreads = threads,
                ),
                languageSetting = { config, language ->
                    config.modelConfig.canary.srcLang = language
                    config.modelConfig.canary.tgtLang = language
                },
                supported = model.languages,
            )
            Engine.MOONSHINE -> SherpaOffline(
                OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        encoder = path("encoder_model.ort"),
                        mergedDecoder = path("decoder_model_merged.ort"),
                    ),
                    tokens = path("tokens.txt"),
                    numThreads = threads,
                ),
            )
            Engine.KROKO -> SherpaOnline(
                OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = path("encoder.onnx"),
                        decoder = path("decoder.onnx"),
                        joiner = path("joiner.onnx"),
                    ),
                    tokens = path("tokens.txt"),
                    modelType = "zipformer2",
                    numThreads = threads,
                ),
            )
        }
    }
}

private const val RATE = 16_000

/** whisper.cpp through the core. */
private class WhisperRecognizer(path: String) : Recognizer {
    private val handle = Core.voiceLoad(path).also { require(it != 0L) { "whisper could not load $path" } }
    override fun transcribe(audio: FloatArray, languages: List<String>) =
        Core.voiceTranscribe(handle, audio, languages.joinToString(","))
    override fun release() = Core.voiceFree(handle)
}

/**
 * A sherpa-onnx model that hears a whole stretch at once. Canary has to be
 * told the language: the first of the user's languages it knows, which
 * means building it again when that changes.
 */
private class SherpaOffline(
    model: OfflineModelConfig,
    private val languageSetting: ((OfflineRecognizerConfig, String) -> Unit)? = null,
    private val supported: List<String>? = null,
) : Recognizer {
    private val config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80), modelConfig = model)
    private var recognizer: OfflineRecognizer? = null
    private var language: String? = null

    override fun transcribe(audio: FloatArray, languages: List<String>): String {
        val wanted = supported?.let { known -> languages.firstOrNull { it in known } ?: known.first() }
        if (recognizer == null || wanted != language) {
            recognizer?.release()
            if (wanted != null) languageSetting?.invoke(config, wanted)
            recognizer = OfflineRecognizer(null, config)
            language = wanted
        }
        val recognizer = recognizer!!
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio, RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun release() {
        recognizer?.release()
    }
}

/**
 * A sherpa-onnx streaming model, given a stretch the same way as the
 * others: fed, finished, and decoded until it has nothing left.
 */
private class SherpaOnline(model: OnlineModelConfig) : Recognizer {
    private val recognizer = OnlineRecognizer(
        null,
        OnlineRecognizerConfig(featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80), modelConfig = model),
    )

    override fun transcribe(audio: FloatArray, languages: List<String>): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio, RATE)
            // Silence after the speech lets the model finish its last word.
            stream.acceptWaveform(FloatArray(RATE / 2), RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun release() = recognizer.release()
}
