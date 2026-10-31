package com.polylingua.edge.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import com.polylingua.edge.ai.AiCoach
import com.polylingua.edge.ai.ProviderHealth
import com.polylingua.edge.core.AccentPreference
import com.polylingua.edge.core.AiProviderKind
import com.polylingua.edge.core.DiagLog
import com.polylingua.edge.core.NativeLoadGuard
import com.polylingua.edge.core.SecureKeyStore
import com.polylingua.edge.core.UserNotices
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.core.MiuiCompat
import com.polylingua.edge.core.ModelStorage
import com.polylingua.edge.core.UserSettings
import com.polylingua.edge.data.CaseRepository
import com.polylingua.edge.data.ExcelDynamicIngestor
import com.polylingua.edge.data.ImportProgress
import com.polylingua.edge.data.PackageEntity
import com.polylingua.edge.data.SessionArtifactStore
import com.polylingua.edge.data.SessionResultEntity
import com.polylingua.edge.data.StoredSession
import com.polylingua.edge.data.WorkbookPreview
import com.polylingua.edge.domain.model.CefrLevel
import com.polylingua.edge.speech.AsrBackend
import com.polylingua.edge.speech.LlmState
import com.polylingua.edge.speech.LocalLlmEngine
import com.polylingua.edge.speech.ModelCatalog
import com.polylingua.edge.speech.ModelManager
import com.polylingua.edge.speech.ModelPack
import com.polylingua.edge.speech.PackStatus
import com.polylingua.edge.speech.SpeechRecognitionEngine
import com.polylingua.edge.speech.SpeechSynthesisEngine
import com.polylingua.edge.speech.StorageRoot
import com.polylingua.edge.speech.SynthVoice
import com.polylingua.edge.speech.SystemTtsInfo
import com.polylingua.edge.speech.VoiceHealth
import com.polylingua.edge.ui.DashboardPayload
import com.polylingua.edge.ui.DiagnosticSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class ImportUiState(
    val preview: WorkbookPreview? = null,
    val selected: Set<Int> = emptySet(),
    val inspecting: Boolean = false,
    val progress: ImportProgress? = null,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CaseRepository,
    private val ingestor: ExcelDynamicIngestor,
    private val vault: SessionArtifactStore,
    private val models: ModelManager,
    private val asr: SpeechRecognitionEngine,
    private val store: DiagnosticSessionStore,
    val settings: AppSettings,
    private val tts: SpeechSynthesisEngine,
) : ViewModel() {

    init {
        // Warm the default neural voice so the examiner's first question plays without a pause.
        viewModelScope.launch {
            kotlinx.coroutines.delay(2500)
            runCatching { tts.prewarm() }
        }
    }

    val packages: StateFlow<List<PackageEntity>> = repository.packages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val caseCount: StateFlow<Int> = repository.caseCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val history: StateFlow<List<SessionResultEntity>> = vault.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val essentialsReady: StateFlow<Int> = models.statuses.map { m ->
        ModelCatalog.ESSENTIALS.count { m[it.id] is PackStatus.Installed }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _import = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _import

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    val isMiui: Boolean = MiuiCompat.isMiui
    val asrBackend: AsrBackend get() = asr.backend()

    fun consumeMessage() { _message.value = null }

    // ------------------------------------------------------------------- Excel ingest

    fun inspectWorkbook(uri: Uri) {
        _import.value = ImportUiState(inspecting = true)
        viewModelScope.launch {
            runCatching { ingestor.inspect(uri) }
                .onSuccess { p ->
                    val all = p.sheets.indices.take(ExcelDynamicIngestor.MAX_SHEETS).toSet()
                    _import.value = ImportUiState(preview = p, selected = all)
                }
                .onFailure { _import.value = ImportUiState(error = it.message ?: "Could not read the workbook") }
        }
    }

    fun toggleSheet(i: Int) {
        val s = _import.value
        val next = if (i in s.selected) s.selected - i else if (s.selected.size < ExcelDynamicIngestor.MAX_SHEETS) s.selected + i else s.selected
        _import.value = s.copy(selected = next)
    }

    fun selectSheets(indices: Collection<Int>, select: Boolean) {
        val s = _import.value
        val next = if (select) (s.selected + indices).take(ExcelDynamicIngestor.MAX_SHEETS).toSet() else s.selected - indices.toSet()
        _import.value = s.copy(selected = next)
    }

    fun startImport() {
        val s = _import.value
        val preview = s.preview ?: return
        viewModelScope.launch {
            ingestor.import(preview, s.selected.toList()).collect { p ->
                _import.value = _import.value.copy(progress = p)
                if (p is ImportProgress.Done) {
                    _message.value = "Imported ${p.cases} cases from ${p.packages} sheet(s) of ${preview.displayName}."
                }
            }
        }
    }

    fun dismissImport() {
        val p = _import.value.progress
        if (p is ImportProgress.Running) return
        _import.value = ImportUiState()
    }

    fun exportBuiltIn() {
        if (_exporting.value) return
        _exporting.value = true
        viewModelScope.launch {
            runCatching { ingestor.exportBuiltInWorkbook() }
                .onSuccess { _message.value = "Saved ${it.rows} cases (${it.sheets} sheets) to ${it.displayPath}" }
                .onFailure { _message.value = "Export failed: ${it.message}" }
            _exporting.value = false
        }
    }

    fun deletePackage(p: PackageEntity) {
        viewModelScope.launch { repository.deletePackage(p.id) }
    }

    // --------------------------------------------------------------- history vault

    /** Loads a saved session into the dashboard; returns true on success. */
    suspend fun hydrate(entity: SessionResultEntity): Boolean = runCatching {
        val stored = vault.load(File(entity.jsonPath))
        publish(stored, entity.id, entity.caseId)
        true
    }.getOrElse {
        _message.value = "Could not open ${entity.stem}: ${it.message}"
        false
    }

    suspend fun hydrate(uri: Uri): Boolean = runCatching {
        val stored = vault.load(uri)
        publish(stored, null, null)
        true
    }.getOrElse {
        _message.value = "Not a PolyLingua session file: ${it.message}"
        false
    }

    private suspend fun publish(stored: StoredSession, id: Long?, caseId: Long?) {
        val audio = withContext(CoreExecutors.dsp) { vault.loadAudio(stored) }
        val video = stored.videoFile.takeIf { it.isNotBlank() }?.let { File(vault.dir, it) }?.takeIf { it.isFile }?.absolutePath
        store.current.value = DashboardPayload(stored.context, stored.assessment, stored.sample, stored.transcriptEngine, stored.stem, id, audio, caseId, video, stored.bodyLanguage)
    }

    fun deleteSession(e: SessionResultEntity) {
        viewModelScope.launch { vault.delete(e) }
    }

    fun shareIntent(e: SessionResultEntity): Intent = Intent.createChooser(vault.shareIntent(e), "Share ${e.stem}")

    fun setTargetLevel(level: CefrLevel) {
        viewModelScope.launch { settings.update { it.copy(targetCefr = level, targetBand = level.midBand.coerceAtLeast(4.0)) } }
    }

    fun openMiuiPermissions() = MiuiCompat.openPermissionEditor(context)
    fun requestBatteryExemption() = MiuiCompat.requestBatteryExemption(context)
    fun batteryExempt(): Boolean = MiuiCompat.isIgnoringBatteryOptimizations(context)
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
    val settings: AppSettings,
    private val tts: SpeechSynthesisEngine,
    private val llm: LocalLlmEngine,
    private val ai: AiCoach,
    private val keys: SecureKeyStore,
    private val asr: SpeechRecognitionEngine,
    private val guard: NativeLoadGuard,
) : ViewModel() {
    val statuses: StateFlow<Map<String, PackStatus>> = models.statuses
    val llmState: StateFlow<LlmState> = llm.state
    val userSettings: StateFlow<UserSettings> = settings.settings
    val voiceHealth: StateFlow<Map<String, VoiceHealth>> = tts.health
    val systemTts: StateFlow<SystemTtsInfo> = tts.systemInfo
    val aiHealth: StateFlow<Map<AiProviderKind, ProviderHealth>> = ai.health
    val keyRevision: StateFlow<Int> = keys.revision
    val diagLines: StateFlow<Int> = DiagLog.count

    private val _roots = MutableStateFlow<List<StorageRoot>>(emptyList())
    val roots: StateFlow<List<StorageRoot>> = _roots

    private val _voices = MutableStateFlow<List<SynthVoice>>(emptyList())
    val voices: StateFlow<List<SynthVoice>> = _voices

    private val _missingVoices = MutableStateFlow<List<SynthVoice>>(emptyList())
    val missingVoices: StateFlow<List<SynthVoice>> = _missingVoices

    private val _busy = MutableStateFlow<String?>(null)
    /** Label of a running test ("Testing voices 3/24", "Testing Gemini…"), null when idle. */
    val busy: StateFlow<String?> = _busy

    init {
        refresh()
        viewModelScope.launch {
            runCatching { tts.refreshSystemVoices() }
            refreshVoices()
        }
        // Newly installed / removed packs change the voice list immediately.
        viewModelScope.launch {
            models.statuses
                .map { m -> m.filterValues { it is PackStatus.Installed }.keys }
                .distinctUntilChanged()
                .collect { refreshVoices() }
        }
    }

    private fun refreshVoices() {
        _voices.value = tts.availableVoices()
        _missingVoices.value = tts.unavailableLocaleVoices()
    }

    fun refresh(rescan: Boolean = true) {
        viewModelScope.launch(CoreExecutors.dsp) {
            if (rescan) models.rescan()
            _roots.value = models.storageRoots()
            refreshVoices()
        }
    }

    fun download(pack: ModelPack) = models.enqueue(listOf(pack))
    fun downloadEssentials() = models.enqueue(ModelCatalog.ESSENTIALS)
    fun cancel(pack: ModelPack) = models.cancel(pack.id)
    fun delete(pack: ModelPack) {
        viewModelScope.launch {
            if (pack.kind == com.polylingua.edge.speech.ModelKind.LLM) llm.releaseForMemory()
            if (pack.kind == com.polylingua.edge.speech.ModelKind.ASR_STREAMING || pack.kind == com.polylingua.edge.speech.ModelKind.ASR_SECOND_PASS) asr.releaseForMemory()
            if (pack.kind == com.polylingua.edge.speech.ModelKind.TTS) tts.releaseForMemory()
            models.delete(pack)
            refresh()
        }
    }

    fun metered(): Boolean = models.isMeteredNetwork()

    fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch { settings.update(transform) }
    }

    fun setStorage(s: ModelStorage) = update { it.copy(modelStorage = s) }
    fun setAccent(a: AccentPreference) = update { it.copy(accent = a) }

    // ------------------------------------------------------------------- voices

    fun previewVoice(v: SynthVoice) {
        viewModelScope.launch {
            tts.speak("Hello! This is how I sound when I read your IELTS model answers.", v, label = v.label)
        }
    }

    fun testAllVoices() {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = "Testing voices…"
            tts.testAll { i, n -> _busy.value = "Testing voices ${minOf(i + 1, n)}/$n…" }
            refreshVoices()
            _busy.value = null
            val failed = tts.health.value.count { it.value is VoiceHealth.Failed }
            UserNotices.post(if (failed == 0) "All voices work ✓" else "$failed voice(s) failed - see the red marks.", if (failed == 0) UserNotices.Kind.SUCCESS else UserNotices.Kind.WARNING)
        }
    }

    private fun launchIntent(vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun openTtsSettings() {
        if (!launchIntent(Intent("com.android.settings.TTS_SETTINGS"), Intent(android.provider.Settings.ACTION_SETTINGS))) {
            UserNotices.post("Open Android Settings → Accessibility → Text-to-speech output.")
        }
    }

    fun installVoiceData() {
        val engine = tts.systemInfo.value.enginePackage
        val install = Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        if (!launchIntent(if (engine.isNotBlank()) Intent(install).setPackage(engine) else install, install, Intent("com.android.settings.TTS_SETTINGS"))) {
            UserNotices.post("Your text-to-speech engine has no voice-data installer. Install \"Speech Services by Google\" from the Play Store.")
        }
    }

    fun getGoogleTts() {
        launchIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")),
        )
    }

    fun loadLlm() {
        viewModelScope.launch { llm.ensureLoaded() }
    }

    // --------------------------------------------------------------- AI provider

    fun hasKey(slot: SecureKeyStore.Slot): Boolean = keys.has(slot)
    fun maskedKey(slot: SecureKeyStore.Slot): String = keys.masked(slot)

    fun saveKey(slot: SecureKeyStore.Slot, value: String) {
        keys.put(slot, value)
        UserNotices.post(if (value.isBlank()) "${slot.label} removed." else "${slot.label} saved (encrypted on this phone).", UserNotices.Kind.SUCCESS)
    }

    fun setProvider(kind: AiProviderKind) = update { it.copy(aiProvider = kind) }

    fun testProvider(kind: AiProviderKind) {
        if (_busy.value != null) return
        viewModelScope.launch {
            _busy.value = "Testing ${kind.label}…"
            val h = ai.test(kind)
            _busy.value = null
            UserNotices.post((if (h.ok) "✓ " else "✗ ") + h.message, if (h.ok) UserNotices.Kind.SUCCESS else UserNotices.Kind.ERROR)
        }
    }

    fun openUrl(url: String) {
        launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    // ------------------------------------------------------------- recognition

    fun asrSummary(): String = asr.engineSummary()
    fun blockedModels(): Set<String> = guard.blocked
    fun unblock(id: String) {
        guard.unblock(id)
        UserNotices.post("$id re-enabled. If it crashes again it will be disabled automatically.")
    }

    // ------------------------------------------------------------- diagnostics

    fun shareDiagnostics() {
        val s = settings.settings.value
        val header = "Live ASR: ${asr.livePack()?.id ?: "none"} · final: ${asr.secondPassPack()?.id ?: "none"} (${s.finalAsrPackId.ifBlank { "auto" }}) · " +
            "AI: ${s.aiProvider} · TTS engine: ${tts.systemInfo.value.engineLabel} · blocked: ${guard.blocked}"
        val file = DiagLog.export(context, header)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "PolyLingua Edge diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launchIntent(Intent.createChooser(send, "Share diagnostics"))
    }

    fun copyDiagnostics() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("PolyLingua diagnostics", DiagLog.snapshot().takeLast(60_000)))
        UserNotices.post("Diagnostics copied to the clipboard.")
    }

    fun clearDiagnostics() = DiagLog.clear()
}
