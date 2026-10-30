package com.polylingua.edge.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.core.LinguisticResources
import com.polylingua.edge.data.CaseRepository
import com.polylingua.edge.data.PhonemeStatEntity
import com.polylingua.edge.data.PolyLinguaDatabase
import com.polylingua.edge.domain.PhoneticArticulationDatabase
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.domain.model.PracticeCase
import com.polylingua.edge.domain.model.PronunciationResult
import com.polylingua.edge.domain.model.TaskContext
import com.polylingua.edge.dsp.PcmUtils
import com.polylingua.edge.speech.AssessmentPipeline
import com.polylingua.edge.speech.AudioClip
import com.polylingua.edge.speech.DrillOutcome
import com.polylingua.edge.speech.LiveCaptureState
import com.polylingua.edge.speech.SpeakingCaptureController
import com.polylingua.edge.speech.SpeechSynthesisEngine
import com.polylingua.edge.ui.DashboardPayload
import com.polylingua.edge.ui.DiagnosticSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.random.Random

data class WordIpa(val word: String, val ipa: String)

data class DrillUiState(
    val target: String = "",
    val translation: String = "",
    val words: List<WordIpa> = emptyList(),
    val stressHints: List<String> = emptyList(),
    val tempo: Double = 1.0,
    val recording: Boolean = false,
    val analysing: Boolean = false,
    val outcome: DrillOutcome? = null,
    val error: String? = null,
    val attempts: Int = 0,
    val sourceCase: PracticeCase? = null,
)

data class EarTrainerState(
    val arpabet: String = "",
    val pair: Pair<String, String> = "" to "",
    val answerIsFirst: Boolean = true,
    val chosen: Int = -1,
    val correct: Int = 0,
    val total: Int = 0,
)

data class RoleplayState(
    val prompt: PracticeCase? = null,
    val recording: Boolean = false,
    val analysing: Boolean = false,
    val result: com.polylingua.edge.speech.AssessmentOutcome? = null,
    val error: String? = null,
)

/**
 * ELSA-style lab: sentence drills scored per phoneme with GOP, stress dots and an intonation
 * overlay against a neural reference voice; a 44-phoneme library; a minimal-pair ear trainer;
 * and a pronunciation-focused roleplay coach. Running per-phoneme statistics feed "weakest sounds".
 */
@HiltViewModel
class ElsaViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: CaseRepository,
    private val capture: SpeakingCaptureController,
    private val pipeline: AssessmentPipeline,
    private val tts: SpeechSynthesisEngine,
    private val resources: LinguisticResources,
    private val settings: AppSettings,
    private val store: DiagnosticSessionStore,
    db: PolyLinguaDatabase,
) : ViewModel() {
    private val studyDao = db.studyDao()
    private val caseId: Long = savedState["caseId"] ?: -1L

    private val _drill = MutableStateFlow(DrillUiState())
    val drill: StateFlow<DrillUiState> = _drill
    private val _ear = MutableStateFlow(EarTrainerState())
    val ear: StateFlow<EarTrainerState> = _ear
    private val _roleplay = MutableStateFlow(RoleplayState())
    val roleplay: StateFlow<RoleplayState> = _roleplay
    private val _live = MutableStateFlow(LiveCaptureState())
    val live: StateFlow<LiveCaptureState> = _live

    val weakest: StateFlow<List<PhonemeStatEntity>> = studyDao.weakestPhonemes(8).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val playback = tts.player.state

    private var reference: AudioClip? = null
    private var referenceKey = ""
    private var active: SpeakingCaptureController.ActiveCapture? = null
    private var liveJob: Job? = null
    private var stopJob: Job? = null

    init {
        viewModelScope.launch {
            repository.ensureBuiltInSeeded()
            val c = if (caseId > 0) repository.caseById(caseId) else null
            if (c != null) setTarget(c.referenceTranscript, c.translationId, c) else nextDrill()
            nextEarItem()
            nextRoleplay()
        }
    }

    // ------------------------------------------------------------------------ drill

    fun nextDrill() {
        viewModelScope.launch {
            val level = settings.settings.value.targetCefr
            val c = repository.randomCases(ModuleType.ELSA_PHONEME_DRILL, level, 1).firstOrNull()
                ?: repository.randomCases(ModuleType.CAKE_SHADOWING, level, 1).firstOrNull()
            if (c != null) setTarget(c.referenceTranscript, c.translationId, c)
        }
    }

    /** Drills a single phoneme using its example words and minimal pairs. */
    fun drillPhoneme(arpabet: String) {
        val p = PhoneticArticulationDatabase.lookup(arpabet) ?: return
        val words = (p.examples + p.minimalPairs.map { it.first }).distinct().take(5)
        viewModelScope.launch { setTarget(words.joinToString(", ") + ".", "", null) }
    }

    fun setCustomTarget(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { setTarget(text.trim(), "", null) }
    }

    private suspend fun setTarget(text: String, translation: String, case: PracticeCase?) {
        val words = withContext(CoreExecutors.dsp) {
            text.split(Regex("\\s+")).filter { it.isNotBlank() }.map { WordIpa(it, resources.ipaOf(it, rp = settings.settings.value.accent.name == "BRITISH_RP")) }
        }
        val stress = withContext(CoreExecutors.dsp) {
            words.mapNotNull { w ->
                val clean = w.word.trim('.', ',', '!', '?', ';', ':').lowercase()
                val pron = resources.cmu.pronounce(clean)
                if (PhoneticArticulationDatabase.syllableCount(pron) >= 2) "${clean} (${PhoneticArticulationDatabase.stressPattern(pron)})" else null
            }.distinct()
        }
        reference = null
        _drill.value = DrillUiState(target = text, translation = translation, words = words, stressHints = stress, tempo = _drill.value.tempo, sourceCase = case)
    }

    fun setTempo(t: Double) { _drill.value = _drill.value.copy(tempo = t) }

    private suspend fun ensureReference(): AudioClip? {
        val key = "${_drill.value.target}|${tts.defaultVoice().id}"
        if (reference == null || referenceKey != key) {
            reference = tts.synthesize(_drill.value.target, tts.defaultVoice(), 1.0)
            referenceKey = key
        }
        return reference
    }

    fun listen() {
        viewModelScope.launch {
            val clip = ensureReference() ?: run {
                _drill.value = _drill.value.copy(error = "No voice available: install a neural voice pack or enable Android TTS.")
                return@launch
            }
            val t = _drill.value.tempo
            val play = if (t == 1.0) clip else withContext(CoreExecutors.dsp) {
                AudioClip(com.polylingua.edge.dsp.TarsosWsolaTimeStretcher.stretch(clip.samples, clip.sampleRate, t), clip.sampleRate)
            }
            tts.player.play(play, "Model voice")
        }
    }

    fun speakWord(word: String) {
        viewModelScope.launch { tts.speak(word.trim(',', '.'), label = word) }
    }

    fun toggleRecord() {
        if (_drill.value.recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        tts.stop()
        viewModelScope.launch {
            val cap = capture.begin(viewModelScope, "ELSA drill")
            if (cap == null) {
                _drill.value = _drill.value.copy(error = "Microphone or speech recogniser unavailable.")
                return@launch
            }
            active = cap
            _drill.value = _drill.value.copy(recording = true, error = null, outcome = null)
            liveJob = viewModelScope.launch { cap.live.collect { _live.value = it } }
            val words = _drill.value.words.size
            stopJob = viewModelScope.launch {
                delay((4000L + words * 900L).coerceAtMost(20_000L))
                stopRecording()
            }
        }
    }

    private fun stopRecording() {
        val cap = active ?: return
        active = null
        stopJob?.cancel()
        viewModelScope.launch {
            _drill.value = _drill.value.copy(recording = false, analysing = true)
            val captured = cap.end()
            liveJob?.cancel()
            val outcome = runCatching { pipeline.assessDrill(captured, _drill.value.target, ensureReference()) }.getOrNull()
            outcome?.pronunciation?.let { recordStats(it) }
            _drill.value = _drill.value.copy(analysing = false, outcome = outcome, attempts = _drill.value.attempts + 1,
                error = if (outcome == null) "Could not score this attempt - try again in a quieter place." else null)
        }
    }

    private suspend fun recordStats(result: PronunciationResult) {
        if (result.method != "gop") return
        val now = System.currentTimeMillis()
        result.words.flatMap { it.phonemes }.groupBy { PhoneticArticulationDatabase.baseSymbol(it.arpabet) }.forEach { (sym, list) ->
            val prev = studyDao.phoneme(sym)
            val n = (prev?.attempts ?: 0) + list.size
            val mean = ((prev?.meanScore ?: 0.0) * (prev?.attempts ?: 0) + list.sumOf { it.score }) / n
            studyDao.upsertPhoneme(PhonemeStatEntity(sym, n, mean, list.last().score, now))
        }
    }

    // --------------------------------------------------------------- ear trainer

    fun nextEarItem() {
        val withPairs = PhoneticArticulationDatabase.PHONEMES.filter { it.minimalPairs.isNotEmpty() && it.arpabet != null }
        if (withPairs.isEmpty()) return
        val weak = weakest.value.map { it.arpabet }.toSet()
        val pool = withPairs.filter { it.arpabet in weak }.ifEmpty { withPairs }
        val p = pool[Random.nextInt(pool.size)]
        val pair = p.minimalPairs[Random.nextInt(p.minimalPairs.size)]
        val s = _ear.value
        _ear.value = s.copy(arpabet = p.arpabet ?: "", pair = pair, answerIsFirst = Random.nextBoolean(), chosen = -1)
    }

    fun playEar() {
        val s = _ear.value
        val word = if (s.answerIsFirst) s.pair.first else s.pair.second
        viewModelScope.launch { tts.speak(word, label = "Which word?") }
    }

    fun answerEar(first: Boolean) {
        val s = _ear.value
        if (s.chosen >= 0) return
        val ok = first == s.answerIsFirst
        _ear.value = s.copy(chosen = if (first) 0 else 1, correct = s.correct + if (ok) 1 else 0, total = s.total + 1)
    }

    // ------------------------------------------------------------------ roleplay

    fun nextRoleplay() {
        viewModelScope.launch {
            val c = repository.randomCases(ModuleType.HELLOTALK_ROLEPLAY, settings.settings.value.targetCefr, 1).firstOrNull()
            _roleplay.value = RoleplayState(prompt = c)
        }
    }

    fun playRoleplayPrompt() {
        val c = _roleplay.value.prompt ?: return
        viewModelScope.launch { tts.speak(c.prompt, tts.resolve(listOf("kokoro:bf_emma", "kokoro:af_sarah", "system:en-GB")), label = "Coach") }
    }

    fun toggleRoleplayRecord() {
        val s = _roleplay.value
        if (s.recording) {
            val cap = active ?: return
            active = null
            viewModelScope.launch {
                _roleplay.value = _roleplay.value.copy(recording = false, analysing = true)
                val captured = cap.end()
                liveJob?.cancel()
                val case = s.prompt
                val ctx = TaskContext(ModuleType.HELLOTALK_ROLEPLAY, case?.prompt.orEmpty(), case?.topic.orEmpty(), settings.settings.value.targetBand, 60)
                val outcome = runCatching { pipeline.assessSpeaking(captured, ctx) }.getOrNull()
                if (outcome != null) {
                    store.current.value = DashboardPayload(ctx, outcome.assessment, outcome.sample, outcome.transcriptEngine, null, null,
                        if (captured.audio.isNotEmpty()) PcmUtils.shortsToFloats(captured.audio) else null, case?.id)
                    outcome.sample.pronunciation?.let { recordStats(it) }
                }
                _roleplay.value = _roleplay.value.copy(analysing = false, result = outcome,
                    error = if (outcome == null || captured.transcript.text.isBlank()) "Nothing was recognised - try again." else null)
            }
        } else {
            tts.stop()
            viewModelScope.launch {
                val cap = capture.begin(viewModelScope, "Roleplay coach") ?: run {
                    _roleplay.value = _roleplay.value.copy(error = "Microphone or speech recogniser unavailable.")
                    return@launch
                }
                active = cap
                liveJob = viewModelScope.launch { cap.live.collect { _live.value = it } }
                _roleplay.value = _roleplay.value.copy(recording = true, result = null, error = null)
            }
        }
    }

    override fun onCleared() {
        stopJob?.cancel()
        liveJob?.cancel()
        active?.abort()
        tts.stop()
    }
}
