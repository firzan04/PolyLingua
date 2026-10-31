package com.polylingua.edge.speech

/** What a downloadable model pack is used for. */
enum class ModelKind(val title: String) {
    VAD("Voice activity detection"),
    ASR_STREAMING("Live speech recognition"),
    ASR_SECOND_PASS("High-accuracy transcript"),
    PUNCTUATION("Punctuation & casing"),
    TTS("Neural voices"),
    PHONEME("Phoneme GOP scoring"),
    LLM("Offline AI coach"),
}

/**
 * How a speech-recognition pack is loaded by the native bridge.
 * [kind]: streaming "transducer"; offline "nemo_transducer" | "nemo_ctc" | "moonshine".
 * [files] are the model files in loader order (encoder, decoder, joiner) or the single model.
 * [rank]: higher = more accurate; "Auto" picks the highest-ranked installed pack.
 */
data class AsrSpec(
    val kind: String,
    val files: List<String>,
    val tokens: String = "tokens.txt",
    val modelType: String = "",
    val threads: Int = 1,
    val rank: Int,
    val engineTag: String,
)

/**
 * One downloadable model pack. Archives (.tar.bz2) are streamed through a filter that keeps only
 * [requiredFiles] (matched by path relative to the archive's top-level folder) plus any directory
 * listed in [keepDirs]; single-file packs are stored as `requiredFiles[0]`.
 */
data class ModelPack(
    val id: String,
    val title: String,
    val kind: ModelKind,
    val url: String,
    val sizeBytes: Long,
    val archive: Boolean,
    val requiredFiles: List<String>,
    val keepDirs: List<String> = emptyList(),
    val description: String,
    val license: String,
    val recommended: Boolean = false,
    /** Approximate resident memory once loaded, for the 1.8 GB residency budget. */
    val residentMb: Int,
    val fallbackUrls: List<String> = emptyList(),
    val asr: AsrSpec? = null,
) {
    val sizeMb: Int get() = ((sizeBytes + 524_288) / 1_048_576).toInt()
}

/** Voice exposed by a TTS pack (speaker id within the model). */
data class NeuralVoice(
    val id: String,
    val packId: String,
    val speakerId: Int,
    val name: String,
    val accent: String,      // "en-GB", "en-US", "en-AU"...
    val female: Boolean,
)

object ModelCatalog {
    private const val SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    private const val HF = "https://huggingface.co"

    val VAD_SILERO = ModelPack(
        id = "vad-silero",
        title = "Silero VAD",
        kind = ModelKind.VAD,
        url = "$SHERPA/asr-models/silero_vad.onnx",
        sizeBytes = 643_854,
        archive = false,
        requiredFiles = listOf("silero_vad.onnx"),
        description = "Detects speech vs. silence for pause analysis (>600 ms) and phonation-time ratio.",
        license = "MIT (snakers4/silero-vad)",
        recommended = true,
        residentMb = 8,
    )

    val ASR_ZIPFORMER = ModelPack(
        id = "asr-zipformer-en-20m",
        title = "Streaming Zipformer EN 20M (int8)",
        kind = ModelKind.ASR_STREAMING,
        url = "$SHERPA/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
        sizeBytes = 127_887_156,
        archive = true,
        requiredFiles = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
        ),
        description = "Lite live captions (20 M parameters). Fast but noticeably less accurate than NeMo FastConformer - keep only for very low memory.",
        license = "Apache-2.0 (k2-fsa/icefall)",
        residentMb = 90,
        asr = AsrSpec(
            kind = "transducer",
            files = listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx"),
            rank = 1, engineTag = "zipformer-20m",
        ),
    )

    val ASR_NEMO_STREAMING = ModelPack(
        id = "asr-nemo-fastconformer-480ms",
        title = "NeMo FastConformer streaming EN (480 ms, int8)",
        kind = ModelKind.ASR_STREAMING,
        url = "$SHERPA/asr-models/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8.tar.bz2",
        sizeBytes = 105_913_204,
        archive = true,
        requiredFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        description = "Accurate live captions (114 M parameters, trained on thousands of hours of varied English incl. accented speech). Replaces the 20 M Zipformer.",
        license = "CC-BY-4.0 (NVIDIA NeMo)",
        recommended = true,
        residentMb = 210,
        asr = AsrSpec(
            kind = "transducer",
            files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx"),
            rank = 3, engineTag = "nemo-fc-480ms",
        ),
    )

    val ASR_NEMOTRON = ModelPack(
        id = "asr-nemotron-streaming-0.6b",
        title = "Nemotron Speech Streaming EN 0.6B (int8) · experimental",
        kind = ModelKind.ASR_STREAMING,
        url = "$SHERPA/asr-models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-int8-2026-01-14.tar.bz2",
        sizeBytes = 468_994_262,
        archive = true,
        requiredFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        description = "Highest-accuracy live captions with punctuation. Heavy: uses both big cores; if captions lag on your phone, switch Live captions back to NeMo FastConformer.",
        license = "NVIDIA Open Model License",
        residentMb = 760,
        asr = AsrSpec(
            kind = "transducer",
            files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx"),
            threads = 2, rank = 4, engineTag = "nemotron-0.6b",
        ),
    )

    val ASR_MOONSHINE = ModelPack(
        id = "asr-moonshine-tiny-en",
        title = "Moonshine Tiny EN (int8)",
        kind = ModelKind.ASR_SECOND_PASS,
        url = "$SHERPA/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2",
        sizeBytes = 107_600_538,
        archive = true,
        requiredFiles = listOf(
            "preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt",
        ),
        description = "Small second pass over each utterance. Superseded by Parakeet; keep only if storage is tight.",
        license = "MIT (Useful Sensors)",
        residentMb = 160,
        asr = AsrSpec(
            kind = "moonshine",
            files = listOf("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx"),
            rank = 1, engineTag = "moonshine-tiny",
        ),
    )

    val ASR_PARAKEET_110M = ModelPack(
        id = "asr-parakeet-tdt-ctc-110m",
        title = "Parakeet TDT-CTC 110M EN (int8)",
        kind = ModelKind.ASR_SECOND_PASS,
        url = "$SHERPA/asr-models/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2",
        sizeBytes = 104_337_827,
        archive = true,
        requiredFiles = listOf("model.int8.onnx", "tokens.txt"),
        description = "Fast, accurate final transcript of every answer (NVIDIA Parakeet). Used for grammar, vocabulary and coherence scoring.",
        license = "CC-BY-4.0 (NVIDIA)",
        recommended = true,
        residentMb = 220,
        asr = AsrSpec(kind = "nemo_ctc", files = listOf("model.int8.onnx"), threads = 2, rank = 2, engineTag = "parakeet-110m"),
    )

    val ASR_PARAKEET_06B = ModelPack(
        id = "asr-parakeet-tdt-0.6b-v2",
        title = "Parakeet TDT 0.6B v2 EN (int8) · max accuracy",
        kind = ModelKind.ASR_SECOND_PASS,
        url = "$SHERPA/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
        sizeBytes = 482_468_385,
        archive = true,
        requiredFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        description = "Most accurate offline English transcript (top of the open ASR leaderboards), with punctuation and casing. Slower: adds several seconds after each answer.",
        license = "CC-BY-4.0 (NVIDIA)",
        residentMb = 820,
        asr = AsrSpec(
            kind = "nemo_transducer",
            files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx"),
            threads = 2, rank = 3, engineTag = "parakeet-0.6b",
        ),
    )

    val PUNCTUATION = ModelPack(
        id = "punct-en-online",
        title = "English punctuation + truecasing",
        kind = ModelKind.PUNCTUATION,
        url = "$SHERPA/punctuation-models/sherpa-onnx-online-punct-en-2024-08-06.tar.bz2",
        sizeBytes = 30_667_839,
        archive = true,
        requiredFiles = listOf("model.int8.onnx", "bpe.vocab"),
        description = "Restores sentence boundaries so clause, complexity and pause-at-boundary analysis is accurate.",
        license = "Apache-2.0 (k2-fsa)",
        recommended = true,
        residentMb = 40,
    )

    val TTS_KOKORO = ModelPack(
        id = "tts-kokoro-int8-en",
        title = "Kokoro 82M (int8) · 11 UK/US voices",
        kind = ModelKind.TTS,
        url = "$SHERPA/tts-models/kokoro-int8-en-v0_19.tar.bz2",
        sizeBytes = 103_248_205,
        archive = true,
        requiredFiles = listOf("model.int8.onnx", "voices.bin", "tokens.txt"),
        keepDirs = listOf("espeak-ng-data"),
        description = "Most natural offline voices: British (Emma, Isabella, George, Lewis) and American (Sarah, Nicole, Bella, Sky, Adam, Michael).",
        license = "Apache-2.0 (hexgrad/Kokoro-82M)",
        recommended = true,
        residentMb = 260,
    )

    val TTS_KITTEN = ModelPack(
        id = "tts-kitten-nano-en",
        title = "Kitten Nano (fp16) · 8 voices",
        kind = ModelKind.TTS,
        url = "$SHERPA/tts-models/kitten-nano-en-v0_2-fp16.tar.bz2",
        sizeBytes = 26_586_708,
        archive = true,
        requiredFiles = listOf("model.fp16.onnx", "voices.bin", "tokens.txt"),
        keepDirs = listOf("espeak-ng-data"),
        description = "Tiny and fast American voices - good for quick shadowing loops on low memory.",
        license = "Apache-2.0 (KittenML)",
        residentMb = 90,
    )

    val TTS_PIPER_ALAN = ModelPack(
        id = "tts-piper-en-gb-alan",
        title = "Piper Alan (en-GB, medium)",
        kind = ModelKind.TTS,
        url = "$SHERPA/tts-models/vits-piper-en_GB-alan-medium.tar.bz2",
        sizeBytes = 67_220_121,
        archive = true,
        requiredFiles = listOf("en_GB-alan-medium.onnx", "tokens.txt"),
        keepDirs = listOf("espeak-ng-data"),
        description = "Clear British RP male voice (VITS).",
        license = "MIT (rhasspy/piper)",
        residentMb = 120,
    )

    val TTS_PIPER_LESSAC = ModelPack(
        id = "tts-piper-en-us-lessac",
        title = "Piper Lessac (en-US, medium)",
        kind = ModelKind.TTS,
        url = "$SHERPA/tts-models/vits-piper-en_US-lessac-medium.tar.bz2",
        sizeBytes = 67_230_653,
        archive = true,
        requiredFiles = listOf("en_US-lessac-medium.onnx", "tokens.txt"),
        keepDirs = listOf("espeak-ng-data"),
        description = "Expressive General American female voice (VITS).",
        license = "MIT (rhasspy/piper)",
        residentMb = 120,
    )

    val GOP_CHARSIU = ModelPack(
        id = "gop-charsiu-w2v2",
        title = "Charsiu wav2vec2 phone classifier (int8)",
        kind = ModelKind.PHONEME,
        url = "$HF/mnaoizyyy/charsiu-js-models/resolve/main/en_w2v2_fc_10ms/model_quantized.onnx",
        sizeBytes = 123_000_000,
        archive = false,
        requiredFiles = listOf("model_quantized.onnx"),
        description = "Frame-level phone posteriors (42 CMU classes, 10 ms) for true Goodness-of-Pronunciation scores and substitution detection.",
        license = "MIT (lingjzhu/charsiu)",
        recommended = true,
        residentMb = 220,
    )

    val LLM_QWEN = ModelPack(
        id = "llm-qwen2.5-1.5b-q4_0",
        title = "Qwen2.5 1.5B Instruct (Q4_0)",
        kind = ModelKind.LLM,
        url = "$HF/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_0.gguf",
        fallbackUrls = listOf(
            "$HF/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/c024a9d172916a142bb7d7e1765f091db343d869/Qwen2.5-1.5B-Instruct-Q4_0.gguf",
        ),
        sizeBytes = 938_000_000,
        archive = false,
        requiredFiles = listOf("Qwen2.5-1.5B-Instruct-Q4_0.gguf"),
        description = "Best offline persona partner and grammar coach. ~6-9 tokens/s on 2x Cortex-A76.",
        license = "Apache-2.0 (Qwen)",
        residentMb = 1050,
    )

    val LLM_SMOL = ModelPack(
        id = "llm-smollm2-360m-q8_0",
        title = "SmolLM2 360M Instruct (Q8_0)",
        kind = ModelKind.LLM,
        url = "$HF/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q8_0.gguf",
        sizeBytes = 386_000_000,
        archive = false,
        requiredFiles = listOf("SmolLM2-360M-Instruct-Q8_0.gguf"),
        description = "Lightweight fallback coach (~20 tokens/s); simpler replies.",
        license = "Apache-2.0 (HuggingFaceTB)",
        residentMb = 460,
    )

    val ALL: List<ModelPack> = listOf(
        VAD_SILERO, ASR_NEMO_STREAMING, ASR_NEMOTRON, ASR_ZIPFORMER, PUNCTUATION, GOP_CHARSIU,
        TTS_KOKORO, TTS_KITTEN, TTS_PIPER_ALAN, TTS_PIPER_LESSAC,
        ASR_PARAKEET_110M, ASR_PARAKEET_06B, ASR_MOONSHINE, LLM_QWEN, LLM_SMOL,
    )

    fun byId(id: String): ModelPack? = ALL.firstOrNull { it.id == id }

    /** Live-caption recognisers, most accurate first. */
    val STREAMING_ASR: List<ModelPack> = listOf(ASR_NEMOTRON, ASR_NEMO_STREAMING, ASR_ZIPFORMER)

    /** Final-transcript (second pass) recognisers, most accurate first. */
    val SECOND_PASS_ASR: List<ModelPack> = listOf(ASR_PARAKEET_06B, ASR_PARAKEET_110M, ASR_MOONSHINE)

    /** The essential offline speaking-assessment bundle (~470 MB download). */
    val ESSENTIALS: List<ModelPack> = listOf(VAD_SILERO, ASR_NEMO_STREAMING, PUNCTUATION, ASR_PARAKEET_110M, GOP_CHARSIU, TTS_KOKORO)

    val ESSENTIALS_MB: Int get() = ESSENTIALS.sumOf { it.sizeMb }

    /** Kokoro v0.19 speaker table (sid order of voices.bin). */
    val KOKORO_VOICES: List<NeuralVoice> = listOf(
        NeuralVoice("kokoro:af", TTS_KOKORO.id, 0, "Default (US)", "en-US", true),
        NeuralVoice("kokoro:af_bella", TTS_KOKORO.id, 1, "Bella (US)", "en-US", true),
        NeuralVoice("kokoro:af_nicole", TTS_KOKORO.id, 2, "Nicole (US)", "en-US", true),
        NeuralVoice("kokoro:af_sarah", TTS_KOKORO.id, 3, "Sarah (US)", "en-US", true),
        NeuralVoice("kokoro:af_sky", TTS_KOKORO.id, 4, "Sky (US)", "en-US", true),
        NeuralVoice("kokoro:am_adam", TTS_KOKORO.id, 5, "Adam (US)", "en-US", false),
        NeuralVoice("kokoro:am_michael", TTS_KOKORO.id, 6, "Michael (US)", "en-US", false),
        NeuralVoice("kokoro:bf_emma", TTS_KOKORO.id, 7, "Emma (UK)", "en-GB", true),
        NeuralVoice("kokoro:bf_isabella", TTS_KOKORO.id, 8, "Isabella (UK)", "en-GB", true),
        NeuralVoice("kokoro:bm_george", TTS_KOKORO.id, 9, "George (UK)", "en-GB", false),
        NeuralVoice("kokoro:bm_lewis", TTS_KOKORO.id, 10, "Lewis (UK)", "en-GB", false),
    )

    val KITTEN_VOICES: List<NeuralVoice> = listOf(
        NeuralVoice("kitten:2m", TTS_KITTEN.id, 0, "Kitten 2 (male)", "en-US", false),
        NeuralVoice("kitten:2f", TTS_KITTEN.id, 1, "Kitten 2 (female)", "en-US", true),
        NeuralVoice("kitten:3m", TTS_KITTEN.id, 2, "Kitten 3 (male)", "en-US", false),
        NeuralVoice("kitten:3f", TTS_KITTEN.id, 3, "Kitten 3 (female)", "en-US", true),
        NeuralVoice("kitten:4m", TTS_KITTEN.id, 4, "Kitten 4 (male)", "en-US", false),
        NeuralVoice("kitten:4f", TTS_KITTEN.id, 5, "Kitten 4 (female)", "en-US", true),
        NeuralVoice("kitten:5m", TTS_KITTEN.id, 6, "Kitten 5 (male)", "en-US", false),
        NeuralVoice("kitten:5f", TTS_KITTEN.id, 7, "Kitten 5 (female)", "en-US", true),
    )

    val PIPER_VOICES: List<NeuralVoice> = listOf(
        NeuralVoice("piper:alan", TTS_PIPER_ALAN.id, 0, "Alan (UK)", "en-GB", false),
        NeuralVoice("piper:lessac", TTS_PIPER_LESSAC.id, 0, "Lessac (US)", "en-US", true),
    )

    val NEURAL_VOICES: List<NeuralVoice> = KOKORO_VOICES + KITTEN_VOICES + PIPER_VOICES

    fun voiceById(id: String): NeuralVoice? = NEURAL_VOICES.firstOrNull { it.id == id }
}
