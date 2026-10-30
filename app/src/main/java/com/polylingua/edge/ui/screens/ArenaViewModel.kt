package com.polylingua.edge.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.core.LinguisticResources
import com.polylingua.edge.data.CaseRepository
import com.polylingua.edge.data.SessionArtifactStore
import com.polylingua.edge.domain.TextAnalysis
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.domain.model.PracticeCase
import com.polylingua.edge.domain.model.SpeechSegment
import com.polylingua.edge.domain.model.TaskContext
import com.polylingua.edge.domain.model.WordTiming
import com.polylingua.edge.dsp.OboeAudioRecorder
import com.polylingua.edge.dsp.PcmUtils
import com.polylingua.edge.speech.AsrBackend
import com.polylingua.edge.speech.AssessmentPipeline
import com.polylingua.edge.speech.CapturedResponse
import com.polylingua.edge.speech.FinalTranscript
import com.polylingua.edge.speech.LiveCaptureState
import com.polylingua.edge.speech.SpeakingCaptureController
import com.polylingua.edge.speech.SpeechSynthesisEngine
import com.polylingua.edge.ui.DashboardPayload
import com.polylingua.edge.ui.DiagnosticSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

enum class ArenaPhase { LOADING, READY, COUNTDOWN, EXAMINER, PREP, SPEAKING, BETWEEN, ANALYSING, DONE, ERROR }

data class ArenaQuestion(val case: PracticeCase, val part: Int, val speakSec: Int, val prepSec: Int)

data class ArenaUiState(
    val mock: Boolean = false,
    val phase: ArenaPhase = ArenaPhase.LOADING,
    val questions: List<ArenaQuestion> = emptyList(),
    val index: Int = 0,
    val countdown: Int = 3,
    val prepLeft: Int = 0,
    val speakLeft: Int = 0,
    val notes: String = "",
    val error: String? = null,
    val progressText: String = "",
    val backend: AsrBackend = AsrBackend.NONE,
    val examinerVoice: Boolean = true,
    val topic: String = "",
    val liveIpa: String = "",
    val answered: Int = 0,
) {
    val current: ArenaQuestion? get() = questions.getOrNull(index)
}

/**
 * Stimuler-style arena: 60-second drill or full Part 1 -> Part 2 -> Part 3 mock with examiner
 * voice, 3-second countdown, 1-minute cue-card preparation, live captions/IPA/F0/WPM and
 * automatic scoring + saving to the saved_results vault.
 */
@HiltViewModel
class ArenaViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: CaseRepository,
    private val capture: SpeakingCaptureController,
    private val pipeline: AssessmentPipeline,
    private val vault: SessionArtifactStore,
    private val store: DiagnosticSessionStore,
    private val tts: SpeechSynthesisEngine,
    private val resources: LinguisticResources,
    private val settings: AppSettings,
    recorder: OboeAudioRecorder,
) : ViewModel() {

    private val mode: String = savedState["mode"] ?: "drill"
    private val caseId: Long = savedState["caseId"] ?: -1L

    private val _state = MutableStateFlow(ArenaUiState(mock = mode == "mock"))
    val state: StateFlow<ArenaUiState> = _state
    private val _live = MutableStateFlow(LiveCaptureState())
    val live: StateFlow<LiveCaptureState> = _live
    val waveform: StateFlow<FloatArray> = recorder.waveform

    private var active: SpeakingCaptureController.ActiveCapture? = null
    private var flowJob: Job? = null
    private var liveJob: Job? = null
    private var stopRequested = false
    private val responses = ArrayList<Pair<ArenaQuestion, CapturedResponse>>()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        repository.ensureBuiltInSeeded()
        val level = settings.settings.value.targetCefr
        val questions = if (mode == "mock") {
            val plan = repository.buildMockTest(level)
            plan.part1.map { ArenaQuestion(it, 1, 45, 0) } +
                listOfNotNull(plan.part2?.let { ArenaQuestion(it, 2, 120, 60) }) +
                plan.part3.map { ArenaQuestion(it, 3, 60, 0) }
        } else {
            val c = if (caseId > 0) repository.caseById(caseId) else null
            val picked = c ?: listOf(ModuleType.STIMULER_PART1, ModuleType.STIMULER_PART3)
                .flatMap { repository.randomCases(it, level, 1) }.shuffled().firstOrNull()
            listOfNotNull(picked?.let {
                val part = when (it.module) { ModuleType.STIMULER_PART2_CUECARD -> 2; ModuleType.STIMULER_PART3 -> 3; else -> 1 }
                ArenaQuestion(it, part, if (part == 2) 120 else 60, if (part == 2) 60 else 0)
            })
        }
        _state.value = _state.value.copy(
            phase = if (questions.isEmpty()) ArenaPhase.ERROR else ArenaPhase.READY,
            questions = questions,
            topic = questions.firstOrNull()?.case?.topic.orEmpty(),
            backend = capture.backend,
            error = if (questions.isEmpty()) "No IELTS speaking questions available. Import a workbook or wait for the built-in curriculum to finish seeding." else null,
        )
    }

    fun setExaminerVoice(on: Boolean) { _state.value = _state.value.copy(examinerVoice = on) }
    fun setNotes(t: String) { _state.value = _state.value.copy(notes = t) }

    fun reshuffle() {
        if (_state.value.phase != ArenaPhase.READY) return
        _state.value = _state.value.copy(phase = ArenaPhase.LOADING)
        viewModelScope.launch { load() }
    }

    fun start() {
        if (_state.value.phase != ArenaPhase.READY) return
        if (capture.backend == AsrBackend.NONE) {
            _state.value = _state.value.copy(phase = ArenaPhase.ERROR, error = "No speech recogniser: download the offline speech pack in Models & settings.")
            return
        }
        responses.clear()
        flowJob = viewModelScope.launch { runSession() }
    }

    private suspend fun runSession() {
        for (i in 3 downTo 1) {
            _state.value = _state.value.copy(phase = ArenaPhase.COUNTDOWN, countdown = i)
            delay(1000)
        }
        val qs = _state.value.questions
        for ((i, q) in qs.withIndex()) {
            _state.value = _state.value.copy(index = i, notes = if (q.part == 2) "" else _state.value.notes)
            if (_state.value.examinerVoice) {
                _state.value = _state.value.copy(phase = ArenaPhase.EXAMINER)
                val examiner = tts.resolve(listOf("kokoro:bf_isabella", "kokoro:bf_emma", "piper:alan", "system:en-GB"))
                tts.speak(q.case.prompt.replace("•", ""), examiner, label = "Examiner")
            }
            if (q.prepSec > 0) {
                stopRequested = false
                for (t in q.prepSec downTo 1) {
                    if (stopRequested) break
                    _state.value = _state.value.copy(phase = ArenaPhase.PREP, prepLeft = t)
                    delay(1000)
                }
            }
            val captured = answer(q) ?: return
            responses += q to captured
            _state.value = _state.value.copy(answered = responses.size)
            if (i < qs.lastIndex) {
                _state.value = _state.value.copy(phase = ArenaPhase.BETWEEN)
                delay(1200)
            }
        }
        analyse()
    }

    /** Records one answer with a hard time limit; returns null when capture failed. */
    private suspend fun answer(q: ArenaQuestion): CapturedResponse? {
        stopRequested = false
        val cap = capture.begin(viewModelScope, "IELTS Part ${q.part}: ${q.case.prompt.take(40)}")
        if (cap == null) {
            _state.value = _state.value.copy(phase = ArenaPhase.ERROR, error = "Microphone or recogniser unavailable. Check the microphone permission.")
            return null
        }
        active = cap
        liveJob = viewModelScope.launch {
            var lastIpaWords = 0
            cap.live.collect { l ->
                _live.value = l
                val words = l.transcript.words
                if (words.size != lastIpaWords) {
                    lastIpaWords = words.size
                    val tail = words.takeLast(6).joinToString(" ") { it.word }
                    val ipa = withContext(CoreExecutors.dsp) { resources.ipaOfSentence(tail) }
                    _state.value = _state.value.copy(liveIpa = ipa)
                }
            }
        }
        val start = System.currentTimeMillis()
        while (!stopRequested) {
            val left = q.speakSec - ((System.currentTimeMillis() - start) / 1000).toInt()
            _state.value = _state.value.copy(phase = ArenaPhase.SPEAKING, speakLeft = left.coerceAtLeast(0))
            if (left <= 0) break
            delay(200)
        }
        _state.value = _state.value.copy(phase = ArenaPhase.ANALYSING, progressText = "Finalising transcript…")
        val captured = cap.end(stopMicrophone = true)
        liveJob?.cancel()
        active = null
        return captured
    }

    fun stopAnswer() { stopRequested = true }

    fun skipPrep() { stopRequested = true }

    private suspend fun analyse() {
        _state.value = _state.value.copy(phase = ArenaPhase.ANALYSING, progressText = "Phoneme GOP, prosody and rubric scoring…")
        val st = _state.value
        val combined = if (responses.size == 1) responses.first().second else combine(responses.map { it.second })
        val first = responses.firstOrNull()?.first ?: return
        val context = if (st.mock) {
            val cue = responses.firstOrNull { it.first.part == 2 }?.first?.case
            TaskContext(
                module = ModuleType.STIMULER_PART2_CUECARD,
                prompt = "Full IELTS Speaking mock · ${st.topic.ifBlank { "mixed topics" }}" + (cue?.let { " · ${it.prompt.lineSequence().first()}" } ?: ""),
                topic = st.topic,
                targetBand = settings.settings.value.targetBand,
                allocatedSec = responses.sumOf { it.first.speakSec },
            )
        } else {
            TaskContext(first.case.module, first.case.prompt, first.case.topic, settings.settings.value.targetBand, first.speakSec)
        }
        if (combined.transcript.text.isBlank()) {
            _state.value = _state.value.copy(phase = ArenaPhase.ERROR, error = "No speech was recognised. Speak closer to the microphone and try again.")
            return
        }
        runCatching {
            val outcome = pipeline.assessSpeaking(combined, context)
            _state.value = _state.value.copy(progressText = "Saving to saved_results/…")
            val saved = vault.save(context, outcome.assessment, outcome.sample, outcome.transcriptEngine, combined.audio, if (st.mock) null else first.case.id)
            store.current.value = DashboardPayload(
                context, outcome.assessment, outcome.sample, outcome.transcriptEngine, saved.stem, saved.id,
                if (combined.audio.isNotEmpty()) PcmUtils.shortsToFloats(combined.audio) else null,
                if (st.mock) null else first.case.id,
            )
        }.onSuccess {
            _state.value = _state.value.copy(phase = ArenaPhase.DONE)
        }.onFailure {
            _state.value = _state.value.copy(phase = ArenaPhase.ERROR, error = "Assessment failed: ${it.message}")
        }
    }

    /** Joins several answers into one timeline, trimming each answer's lead/trail silence. */
    private fun combine(list: List<CapturedResponse>): CapturedResponse {
        val words = ArrayList<WordTiming>()
        val segments = ArrayList<SpeechSegment>()
        val pitch = ArrayList<com.polylingua.edge.domain.model.PitchFrame>()
        val audio = PcmUtils.PcmAccumulator(16000 * 60 * 6)
        val texts = ArrayList<String>()
        var offset = 0.0
        var engine = ""
        for (r in list) {
            val t = r.transcript
            engine = t.engine
            val a = max(0.0, (t.words.firstOrNull()?.startSec ?: 0.0) - 0.3)
            val b = min(r.durationSec, (t.words.lastOrNull()?.endSec ?: r.durationSec) + 0.4).coerceAtLeast(a)
            fun shift(x: Double) = x - a + offset
            t.words.forEach { words += it.copy(startSec = shift(it.startSec), endSec = shift(it.endSec)) }
            t.speechSegments.filter { it.endSec > a && it.startSec < b }.forEach {
                segments += SpeechSegment(shift(max(a, it.startSec)), shift(min(b, it.endSec)))
            }
            r.pitch.filter { it.timeSec in a..b }.forEach { pitch += it.copy(timeSec = shift(it.timeSec)) }
            if (r.audio.isNotEmpty()) {
                val s = (a * 16000).toInt().coerceIn(0, r.audio.size)
                val e = (b * 16000).toInt().coerceIn(s, r.audio.size)
                val slice = r.audio.copyOfRange(s, e)
                audio.append(slice, slice.size)
            }
            if (t.text.isNotBlank()) texts += TextAnalysis.ensureTerminalPunctuation(t.text.trim())
            offset += b - a
        }
        val samples = audio.snapshot()
        val text = texts.joinToString(" ")
        val transcript = FinalTranscript(text, text.lowercase(), words, segments, offset, engine, samples)
        return CapturedResponse(transcript, pitch, samples, offset)
    }

    fun abort() {
        flowJob?.cancel()
        liveJob?.cancel()
        active?.abort()
        active = null
        tts.stop()
    }

    override fun onCleared() {
        abort()
    }
}
