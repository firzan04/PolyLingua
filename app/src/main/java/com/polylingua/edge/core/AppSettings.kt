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
) {
    companion object {
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
        }
    }
}
