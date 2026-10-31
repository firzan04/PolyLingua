package com.polylingua.edge.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.polylingua.edge.domain.model.CefrLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "polylingua_settings")

enum class ModelStorage { INTERNAL, SD_CARD }

/** Which language model powers persona chat, corrections and the examiner review. */
enum class AiProviderKind(val label: String, val cloud: Boolean) {
    ON_DEVICE("On-device (Qwen / SmolLM, offline)", false),
    GEMINI("Google Gemini (API key)", true),
    CLAUDE("Anthropic Claude (API key)", true),
    OPENAI_COMPAT("OpenAI-compatible endpoint", true);

    companion object {
        fun parse(s: String?): AiProviderKind = entries.firstOrNull { it.name == s } ?: ON_DEVICE
    }
}

enum class AccentPreference(val label: String, val localeTag: String) {
    BRITISH_RP("British RP", "en-GB"),
    AMERICAN_GA("General American", "en-US"),
    AUSTRALIAN("Australian", "en-AU");

    companion object {
        fun parse(s: String?): AccentPreference = entries.firstOrNull { it.name == s } ?: BRITISH_RP
    }
}

data class UserSettings(
    val modelStorage: ModelStorage = ModelStorage.INTERNAL,
    val accent: AccentPreference = AccentPreference.BRITISH_RP,
    val targetBand: Double = 7.0,
    val targetCefr: CefrLevel = CefrLevel.C1,
    val ttsVoiceId: String = "",
    val ttsSpeed: Float = 1.0f,
    val sidetoneGain: Float = 0.85f,
    val sidetoneEnabled: Boolean = true,
    val useLlmCoach: Boolean = true,
    val llmPackId: String = "",
    val showIpa: Boolean = true,
    val showIndonesian: Boolean = true,
    val signalingUrl: String = DEFAULT_SIGNALING_URL,
    val stunUrl: String = DEFAULT_STUN_URL,
    val displayName: String = "",
    val onboardingDone: Boolean = false,
    val cellularDownloadsAllowed: Boolean = false,
    /** Live-caption recogniser pack id ("" = most accurate installed). */
    val liveAsrPackId: String = "",
    /** Final-transcript engine: "" auto, a pack id, [FINAL_ASR_OFF] or [FINAL_ASR_GEMINI]. */
    val finalAsrPackId: String = "",
    /** Silence (s) that ends your turn in conversations with AI partners. */
    val turnEndSilenceSec: Float = 1.8f,
    /** Software level normalisation (gain + 70 Hz high-pass) on the microphone signal. */
    val micAutoGain: Boolean = true,
    val aiProvider: AiProviderKind = AiProviderKind.ON_DEVICE,
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val claudeModel: String = DEFAULT_CLAUDE_MODEL,
    val openAiBaseUrl: String = "",
    val openAiModel: String = "",
    /** Fall back to the on-device model when the cloud provider is unreachable. */
    val aiOfflineFallback: Boolean = true,
    /** Default for the arena's "Video interview" switch (front camera + body-language report). */
    val videoInterview: Boolean = false,
) {
    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"
        const val FINAL_ASR_OFF = "off"
        const val FINAL_ASR_GEMINI = "gemini"
        /** Public MQTT-over-WebSocket broker used as the default WebRTC signalling relay. */
        const val DEFAULT_SIGNALING_URL = "wss://broker.emqx.io:8084/mqtt"
        const val DEFAULT_STUN_URL = "stun:stun.l.google.com:19302"
    }
}

/** Preferences DataStore wrapper exposing a hot [StateFlow] of [UserSettings]. */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val storage = stringPreferencesKey("model_storage")
        val accent = stringPreferencesKey("accent")
        val targetBand = doublePreferencesKey("target_band")
        val targetCefr = stringPreferencesKey("target_cefr")
        val voice = stringPreferencesKey("tts_voice")
        val ttsSpeed = floatPreferencesKey("tts_speed")
        val sidetoneGain = floatPreferencesKey("sidetone_gain")
        val sidetoneEnabled = booleanPreferencesKey("sidetone_enabled")
        val useLlm = booleanPreferencesKey("use_llm")
        val llmPack = stringPreferencesKey("llm_pack")
        val showIpa = booleanPreferencesKey("show_ipa")
        val showIndonesian = booleanPreferencesKey("show_id")
        val signaling = stringPreferencesKey("signaling_url")
        val stun = stringPreferencesKey("stun_url")
        val displayName = stringPreferencesKey("display_name")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val cellular = booleanPreferencesKey("cellular_downloads")
        val liveAsr = stringPreferencesKey("live_asr_pack")
        val finalAsr = stringPreferencesKey("final_asr_pack")
        val turnEnd = floatPreferencesKey("turn_end_silence")
        val micAgc = booleanPreferencesKey("mic_auto_gain")
        val aiProvider = stringPreferencesKey("ai_provider")
        val geminiModel = stringPreferencesKey("gemini_model")
        val claudeModel = stringPreferencesKey("claude_model")
        val openAiBase = stringPreferencesKey("openai_base_url")
        val openAiModel = stringPreferencesKey("openai_model")
        val aiFallback = booleanPreferencesKey("ai_offline_fallback")
        val videoInterview = booleanPreferencesKey("video_interview")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun Preferences.toSettings(): UserSettings {
        val d = UserSettings()
        return UserSettings(
            modelStorage = runCatching { ModelStorage.valueOf(this[Keys.storage] ?: "") }.getOrDefault(d.modelStorage),
            accent = AccentPreference.parse(this[Keys.accent]),
            targetBand = this[Keys.targetBand] ?: d.targetBand,
            targetCefr = CefrLevel.parse(this[Keys.targetCefr]) ?: d.targetCefr,
            ttsVoiceId = this[Keys.voice] ?: d.ttsVoiceId,
            ttsSpeed = this[Keys.ttsSpeed] ?: d.ttsSpeed,
            sidetoneGain = this[Keys.sidetoneGain] ?: d.sidetoneGain,
            sidetoneEnabled = this[Keys.sidetoneEnabled] ?: d.sidetoneEnabled,
            useLlmCoach = this[Keys.useLlm] ?: d.useLlmCoach,
            llmPackId = this[Keys.llmPack] ?: d.llmPackId,
            showIpa = this[Keys.showIpa] ?: d.showIpa,
            showIndonesian = this[Keys.showIndonesian] ?: d.showIndonesian,
            signalingUrl = this[Keys.signaling]?.takeIf { it.isNotBlank() } ?: d.signalingUrl,
            stunUrl = this[Keys.stun]?.takeIf { it.isNotBlank() } ?: d.stunUrl,
            displayName = this[Keys.displayName] ?: d.displayName,
            onboardingDone = this[Keys.onboarding] ?: d.onboardingDone,
            cellularDownloadsAllowed = this[Keys.cellular] ?: d.cellularDownloadsAllowed,
            liveAsrPackId = this[Keys.liveAsr] ?: d.liveAsrPackId,
            finalAsrPackId = this[Keys.finalAsr] ?: d.finalAsrPackId,
            turnEndSilenceSec = (this[Keys.turnEnd] ?: d.turnEndSilenceSec).coerceIn(0.8f, 4f),
            micAutoGain = this[Keys.micAgc] ?: d.micAutoGain,
            aiProvider = AiProviderKind.parse(this[Keys.aiProvider]),
            geminiModel = this[Keys.geminiModel]?.takeIf { it.isNotBlank() } ?: d.geminiModel,
            claudeModel = this[Keys.claudeModel]?.takeIf { it.isNotBlank() } ?: d.claudeModel,
            openAiBaseUrl = this[Keys.openAiBase] ?: d.openAiBaseUrl,
            openAiModel = this[Keys.openAiModel] ?: d.openAiModel,
            aiOfflineFallback = this[Keys.aiFallback] ?: d.aiOfflineFallback,
            videoInterview = this[Keys.videoInterview] ?: d.videoInterview,
        )
    }

    val settings: StateFlow<UserSettings> = context.settingsStore.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, UserSettings())

    suspend fun current(): UserSettings = context.settingsStore.data.first().toSettings()

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.settingsStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.storage] = next.modelStorage.name
            prefs[Keys.accent] = next.accent.name
            prefs[Keys.targetBand] = next.targetBand
            prefs[Keys.targetCefr] = next.targetCefr.name
            prefs[Keys.voice] = next.ttsVoiceId
            prefs[Keys.ttsSpeed] = next.ttsSpeed
            prefs[Keys.sidetoneGain] = next.sidetoneGain
            prefs[Keys.sidetoneEnabled] = next.sidetoneEnabled
            prefs[Keys.useLlm] = next.useLlmCoach
            prefs[Keys.llmPack] = next.llmPackId
            prefs[Keys.showIpa] = next.showIpa
            prefs[Keys.showIndonesian] = next.showIndonesian
            prefs[Keys.signaling] = next.signalingUrl
            prefs[Keys.stun] = next.stunUrl
            prefs[Keys.displayName] = next.displayName
            prefs[Keys.onboarding] = next.onboardingDone
            prefs[Keys.cellular] = next.cellularDownloadsAllowed
            prefs[Keys.liveAsr] = next.liveAsrPackId
            prefs[Keys.finalAsr] = next.finalAsrPackId
            prefs[Keys.turnEnd] = next.turnEndSilenceSec
            prefs[Keys.micAgc] = next.micAutoGain
            prefs[Keys.aiProvider] = next.aiProvider.name
            prefs[Keys.geminiModel] = next.geminiModel
            prefs[Keys.claudeModel] = next.claudeModel
            prefs[Keys.openAiBase] = next.openAiBaseUrl
            prefs[Keys.openAiModel] = next.openAiModel
            prefs[Keys.aiFallback] = next.aiOfflineFallback
            prefs[Keys.videoInterview] = next.videoInterview
        }
    }
}
