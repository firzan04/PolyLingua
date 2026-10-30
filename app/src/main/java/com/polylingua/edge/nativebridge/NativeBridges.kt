package com.polylingua.edge.nativebridge

import android.util.Log

/**
 * Loads libpolylingua_native.so (which links libsherpa-onnx-c-api.so, libonnxruntime.so,
 * liboboe.so and a statically linked llama.cpp). Every bridge object calls [ensureLoaded]
 * from its initializer; callers check [loaded] before touching an external function so a
 * missing/corrupt native library degrades features instead of crashing the app.
 */
object NativeLoader {
    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("polylingua_native")
            loaded = true
            true
        } catch (t: Throwable) {
            loadError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e("PolyLinguaNative", "Native library failed to load", t)
            false
        }
    }
}

/** Big.LITTLE topology detection and thread pinning (cpu_affinity_manager.cpp). */
object CpuAffinityNative {
    init {
        NativeLoader.ensureLoaded()
    }

    /** JSON: {"method","hardware","big":[..],"little":[..],"cores":[{id,maxFreqKhz,capacity,part,name,online}]} */
    external fun nativeDescribeTopology(): String

    /** Pins the calling thread to at most [maxCores] performance cores. 0 = success, else -errno. */
    external fun nativePinToBigCores(maxCores: Int): Int

    /** Pins the calling thread to the efficiency cluster. 0 = success, else -errno. */
    external fun nativePinToLittleCores(): Int

    external fun nativeCurrentAffinity(): IntArray

    /** Number of heavy-inference threads (= big cores, capped at 2; 2 on Helio G96). */
    external fun nativeRecommendedThreads(): Int
}

/** sherpa-onnx C API bridge (sherpa_bridge_jni.cpp). Call only from the big-core executor. */
object SherpaNative {
    init {
        NativeLoader.ensureLoaded()
    }

    // Streaming transducer ASR
    external fun nativeCreateOnlineRecognizer(
        encoder: String, decoder: String, joiner: String, tokens: String, numThreads: Int,
        decodingMethod: String, enableEndpoint: Boolean, rule1: Float, rule2: Float, rule3: Float,
    ): Long

    external fun nativeDestroyOnlineRecognizer(handle: Long)
    external fun nativeCreateOnlineStream(recognizer: Long): Long
    external fun nativeDestroyOnlineStream(stream: Long)
    external fun nativeOnlineAcceptWaveform(stream: Long, sampleRate: Int, samples: FloatArray, n: Int)
    external fun nativeOnlineDecode(recognizer: Long, stream: Long): Int

    /** JSON: {"text":"..","tokens":[..],"timestamps":[..]} */
    external fun nativeOnlineGetResult(recognizer: Long, stream: Long): String
    external fun nativeOnlineIsEndpoint(recognizer: Long, stream: Long): Boolean
    external fun nativeOnlineReset(recognizer: Long, stream: Long)
    external fun nativeOnlineInputFinished(stream: Long)

    // Silero VAD
    external fun nativeCreateVad(
        model: String, threshold: Float, minSilence: Float, minSpeech: Float, windowSize: Int,
        maxSpeech: Float, sampleRate: Int, numThreads: Int, bufferSeconds: Float,
    ): Long

    external fun nativeVadAcceptWaveform(handle: Long, samples: FloatArray, n: Int)
    external fun nativeVadIsSpeech(handle: Long): Boolean

    /** [start0, length0, start1, length1, ...] in samples since the last reset. */
    external fun nativeVadPopSegments(handle: Long): IntArray
    external fun nativeVadFlush(handle: Long)
    external fun nativeVadReset(handle: Long)
    external fun nativeDestroyVad(handle: Long)

    // Offline second-pass ASR: kind = "moonshine" (p1..p4 = preprocess, encode, uncached, cached)
    // or "whisper" (p1 = encoder, p2 = decoder).
    external fun nativeCreateOfflineRecognizer(
        kind: String, p1: String, p2: String, p3: String, p4: String, tokens: String, numThreads: Int,
    ): Long

    external fun nativeOfflineDecode(handle: Long, samples: FloatArray, sampleRate: Int): String
    external fun nativeDestroyOfflineRecognizer(handle: Long)

    // Offline TTS: kind = kokoro | kitten | vits | matcha
    external fun nativeCreateTts(
        kind: String, model: String, voices: String, tokens: String, dataDir: String,
        lexicon: String, vocoder: String, lengthScale: Float, numThreads: Int,
    ): Long

    external fun nativeTtsGenerate(handle: Long, text: String, sid: Int, speed: Float): FloatArray
    external fun nativeTtsSampleRate(handle: Long): Int
    external fun nativeTtsNumSpeakers(handle: Long): Int
    external fun nativeDestroyTts(handle: Long)

    // English punctuation + truecasing (CNN-BiLSTM online model)
    external fun nativeCreateOnlinePunctuation(model: String, bpeVocab: String, numThreads: Int): Long
    external fun nativeAddPunctuation(handle: Long, text: String): String
    external fun nativeDestroyOnlinePunctuation(handle: Long)
}

/** Phoneme posterior extractor for GOP (gop_phoneme_engine_jni.cpp, ONNX Runtime C API). */
object GopNative {
    init {
        NativeLoader.ensureLoaded()
    }

    external fun nativeCreate(modelPath: String, numThreads: Int): Long

    /** Returns [T, C, logp(0,0) .. logp(T-1,C-1)] (log-softmax per frame) or an empty array. */
    external fun nativeComputeLogPosteriors(handle: Long, audio16k: FloatArray): FloatArray
    external fun nativeDestroy(handle: Long)
}

/** llama.cpp evaluator (llama_evaluator_jni.cpp). Call only from the big-core executor. */
object LlamaNative {
    init {
        NativeLoader.ensureLoaded()
    }

    /** Streaming sink for generated UTF-8 bytes (always complete code points). */
    fun interface TokenCallback {
        fun onToken(bytes: ByteArray)
    }

    external fun nativeBackendInit()
    external fun nativeSystemInfo(): String
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nBatch: Int): Long
    external fun nativeModelInfo(handle: Long): String
    external fun nativeGenerate(
        handle: Long, roles: Array<String>, contents: Array<String>, grammar: String?,
        maxTokens: Int, temperature: Float, topP: Float, seed: Int, callback: TokenCallback?,
    ): String

    external fun nativeCancel(handle: Long)
    external fun nativeResetContext(handle: Long)
    external fun nativeFree(handle: Long)
}

/** Oboe full-duplex sidetone + 16 kHz capture (oboe_sidetone_engine.cpp). */
object OboeNative {
    init {
        NativeLoader.ensureLoaded()
    }

    external fun nativeCreate(): Long

    /** 0 = running; negative = open/start failure. Device ids of 0 use default routing. */
    external fun nativeStart(handle: Long, gain: Float, inputDeviceId: Int, outputDeviceId: Int): Int
    external fun nativeStop(handle: Long)
    external fun nativeSetGain(handle: Long, gain: Float)
    external fun nativeSetMonitoring(handle: Long, enabled: Boolean)

    /** Non-blocking read of 16 kHz mono PCM16 samples; returns the number copied. */
    external fun nativeRead(handle: Long, buffer: ShortArray, max: Int): Int
    external fun nativeLatencyMs(handle: Long): Double
    external fun nativeIsRunning(handle: Long): Boolean
    external fun nativeSampleRate(handle: Long): Int
    external fun nativeDestroy(handle: Long)
}
