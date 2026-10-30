package com.polylingua.edge.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polylingua.edge.core.AccentPreference
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
import com.polylingua.edge.ui.DashboardPayload
import com.polylingua.edge.ui.DiagnosticSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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
) : ViewModel() {

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
        store.current.value = DashboardPayload(stored.context, stored.assessment, stored.sample, stored.transcriptEngine, stored.stem, id, audio, caseId)
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
    private val models: ModelManager,
    val settings: AppSettings,
    private val tts: SpeechSynthesisEngine,
    private val llm: LocalLlmEngine,
) : ViewModel() {
    val statuses: StateFlow<Map<String, PackStatus>> = models.statuses
    val llmState: StateFlow<LlmState> = llm.state
    val userSettings: StateFlow<UserSettings> = settings.settings

    private val _roots = MutableStateFlow<List<StorageRoot>>(emptyList())
    val roots: StateFlow<List<StorageRoot>> = _roots

    private val _voices = MutableStateFlow<List<SynthVoice>>(emptyList())
    val voices: StateFlow<List<SynthVoice>> = _voices

    init {
        refresh()
    }

    fun refresh(rescan: Boolean = true) {
        viewModelScope.launch(CoreExecutors.dsp) {
            if (rescan) models.rescan()
            _roots.value = models.storageRoots()
            _voices.value = tts.availableVoices()
        }
    }

    fun download(pack: ModelPack) = models.enqueue(listOf(pack))
    fun downloadEssentials() = models.enqueue(ModelCatalog.ESSENTIALS)
    fun cancel(pack: ModelPack) = models.cancel(pack.id)
    fun delete(pack: ModelPack) {
        viewModelScope.launch {
            if (pack.kind == com.polylingua.edge.speech.ModelKind.LLM) llm.releaseForMemory()
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

    fun previewVoice(v: SynthVoice) {
        viewModelScope.launch {
            tts.speak("Hello! This is how I sound when I read your IELTS model answers.", v, label = v.label)
        }
    }

    fun loadLlm() {
        viewModelScope.launch { llm.ensureLoaded() }
    }
}
