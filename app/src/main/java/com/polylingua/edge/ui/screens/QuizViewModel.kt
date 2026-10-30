package com.polylingua.edge.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.data.CaseRepository
import com.polylingua.edge.data.FlashcardStateEntity
import com.polylingua.edge.data.PackageEntity
import com.polylingua.edge.data.PolyLinguaDatabase
import com.polylingua.edge.data.QuizAttemptEntity
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.domain.model.PracticeCase
import com.polylingua.edge.speech.SpeechSynthesisEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/** One quiz question with its (possibly shuffled) option order. */
data class QuizItem(
    val case: PracticeCase,
    val order: List<Int>,          // display position -> original option index (0..3)
    val chosen: Int? = null,       // original option index chosen
    val flagged: Boolean = false,
    val elapsedMs: Long = 0,
) {
    val correctIndex: Int get() = case.correctOptionIndex()
    val answered: Boolean get() = chosen != null
    val isCorrect: Boolean get() = chosen == correctIndex
}

data class QuizSetup(
    val selectedPackages: Set<Long> = emptySet(),
    val count: Int = 20,
    val shuffle: Boolean = true,
    val instantFeedback: Boolean = true,
    val secondsPerQuestion: Int = 0,   // 0 = untimed (stopwatch only)
)

enum class QuizPhase { SETUP, RUNNING, RESULTS }

data class QuizUiState(
    val phase: QuizPhase = QuizPhase.SETUP,
    val items: List<QuizItem> = emptyList(),
    val index: Int = 0,
    val elapsedMs: Long = 0,
    val limitMs: Long = 0,
    val message: String? = null,
) {
    val current: QuizItem? get() = items.getOrNull(index)
    val score: Int get() = items.count { it.isCorrect }
    val remainingMs: Long get() = if (limitMs > 0) (limitMs - elapsedMs).coerceAtLeast(0) else 0
}

data class FlashState(
    val queue: List<PracticeCase> = emptyList(),
    val position: Int = 0,
    val flipped: Boolean = false,
    val reviewed: Int = 0,
    val again: Int = 0,
    val dueCount: Int = 0,
    val newCount: Int = 0,
    val loading: Boolean = true,
) {
    val current: PracticeCase? get() = queue.getOrNull(position)
}

/**
 * Classic quiz (A-D MCQ with per-option explanations from columns H-K, HH:MM:SS timer, shuffle,
 * flagging, review) and SM-2 spaced-repetition flashcards.
 */
@HiltViewModel
class QuizViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: CaseRepository,
    private val settings: AppSettings,
    private val tts: SpeechSynthesisEngine,
    db: PolyLinguaDatabase,
) : ViewModel() {
    private val studyDao = db.studyDao()
    val flashMode: Boolean = (savedState.get<String>("mode") ?: "quiz") == "flash"
    private val initialPackages: Set<Long> = (savedState.get<String>("packages") ?: "").split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    val packages: StateFlow<List<PackageEntity>> = repository.packages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _setup = MutableStateFlow(QuizSetup(selectedPackages = initialPackages))
    val setup: StateFlow<QuizSetup> = _setup
    private val _quiz = MutableStateFlow(QuizUiState())
    val quiz: StateFlow<QuizUiState> = _quiz
    private val _flash = MutableStateFlow(FlashState())
    val flash: StateFlow<FlashState> = _flash

    private var timerJob: Job? = null
    private var questionStartedAt = 0L
    private var sessionTag = ""

    init {
        if (flashMode) viewModelScope.launch { loadFlashcards() }
    }

    // ---------------------------------------------------------------------- setup

    fun togglePackage(id: Long) {
        val s = _setup.value
        _setup.value = s.copy(selectedPackages = if (id in s.selectedPackages) s.selectedPackages - id else s.selectedPackages + id)
    }

    fun setCount(n: Int) { _setup.value = _setup.value.copy(count = n) }
    fun setShuffle(b: Boolean) { _setup.value = _setup.value.copy(shuffle = b) }
    fun setInstant(b: Boolean) { _setup.value = _setup.value.copy(instantFeedback = b) }
    fun setSecondsPerQuestion(s: Int) { _setup.value = _setup.value.copy(secondsPerQuestion = s) }

    private suspend fun loadCases(module: ModuleType): List<PracticeCase> {
        repository.ensureBuiltInSeeded()
        val sel = _setup.value.selectedPackages
        val fromPackages = if (sel.isNotEmpty()) repository.casesInPackages(sel.toList()).filter { it.module == module } else emptyList()
        return fromPackages.ifEmpty {
            val level = settings.settings.value.targetCefr
            repository.randomCases(module, null, 400).sortedBy { kotlin.math.abs(it.cefr.rank - level.rank) }
        }
    }

    // ----------------------------------------------------------------------- quiz

    fun startQuiz(retryItems: List<PracticeCase>? = null) {
        viewModelScope.launch {
            val s = _setup.value
            val pool = retryItems ?: loadCases(ModuleType.QUIZ_MCQ).filter { c -> c.options.count { it.isNotBlank() } >= 2 }
            if (pool.isEmpty()) {
                _quiz.value = QuizUiState(message = "No multiple-choice questions in the selected packages.")
                return@launch
            }
            val picked = (if (s.shuffle) pool.shuffled() else pool).take(if (retryItems != null) pool.size else s.count)
            val items = picked.map { c ->
                val valid = (0..3).filter { c.options[it].isNotBlank() }
                QuizItem(c, if (s.shuffle) valid.shuffled() else valid)
            }
            sessionTag = UUID.randomUUID().toString()
            _quiz.value = QuizUiState(phase = QuizPhase.RUNNING, items = items, limitMs = s.secondsPerQuestion * 1000L * items.size)
            questionStartedAt = System.currentTimeMillis()
            startTimer()
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        val start = System.currentTimeMillis() - _quiz.value.elapsedMs
        timerJob = viewModelScope.launch {
            while (isActive && _quiz.value.phase == QuizPhase.RUNNING) {
                val elapsed = System.currentTimeMillis() - start
                _quiz.value = _quiz.value.copy(elapsedMs = elapsed)
                if (_quiz.value.limitMs > 0 && elapsed >= _quiz.value.limitMs) {
                    finishQuiz()
                    break
                }
                delay(250)
            }
        }
    }

    fun choose(displayPosition: Int) {
        val q = _quiz.value
        val item = q.current ?: return
        if (item.answered && _setup.value.instantFeedback) return
        val original = item.order.getOrNull(displayPosition) ?: return
        val spent = System.currentTimeMillis() - questionStartedAt
        val updated = item.copy(chosen = original, elapsedMs = item.elapsedMs + spent)
        questionStartedAt = System.currentTimeMillis()
        _quiz.value = q.copy(items = q.items.toMutableList().also { it[q.index] = updated })
        viewModelScope.launch {
            studyDao.insertAttempt(QuizAttemptEntity(caseId = item.case.id, sessionTag = sessionTag, chosenIndex = original,
                correct = original == item.correctIndex, elapsedMs = spent, flagged = item.flagged, attemptedAt = System.currentTimeMillis()))
        }
    }

    fun toggleFlag() {
        val q = _quiz.value
        val item = q.current ?: return
        _quiz.value = q.copy(items = q.items.toMutableList().also { it[q.index] = item.copy(flagged = !item.flagged) })
    }

    fun goTo(i: Int) {
        val q = _quiz.value
        if (i !in q.items.indices) return
        questionStartedAt = System.currentTimeMillis()
        _quiz.value = q.copy(index = i)
    }

    fun next() = goTo(_quiz.value.index + 1)
    fun prev() = goTo(_quiz.value.index - 1)

    fun finishQuiz() {
        timerJob?.cancel()
        _quiz.value = _quiz.value.copy(phase = QuizPhase.RESULTS)
    }

    fun retryMissed() {
        val missed = _quiz.value.items.filter { !it.isCorrect || it.flagged }.map { it.case }
        if (missed.isEmpty()) return
        startQuiz(missed)
    }

    fun backToSetup() {
        timerJob?.cancel()
        _quiz.value = QuizUiState()
    }

    fun speak(text: String) {
        viewModelScope.launch { tts.speak(text, label = "Quiz") }
    }

    // ------------------------------------------------------------------ flashcards

    private suspend fun loadFlashcards() {
        _flash.value = FlashState(loading = true)
        val cards = loadCases(ModuleType.FLASHCARD) + loadCases(ModuleType.QUIZ_MCQ).take(60)
        val states = studyDao.cards(cards.map { it.id }).associateBy { it.caseId }
        val now = System.currentTimeMillis()
        val due = cards.filter { states[it.id]?.let { s -> s.dueAt <= now } == true }.sortedBy { states[it.id]?.dueAt }
        val fresh = cards.filter { states[it.id] == null }.shuffled().take(20)
        val queue = due + fresh
        _flash.value = FlashState(queue = queue, dueCount = due.size, newCount = fresh.size, loading = false)
    }

    fun reloadFlashcards() { viewModelScope.launch { loadFlashcards() } }

    fun flip() { _flash.value = _flash.value.copy(flipped = !_flash.value.flipped) }

    /** SM-2 grade: 1 = again, 3 = hard, 4 = good, 5 = easy. */
    fun grade(quality: Int) {
        val f = _flash.value
        val card = f.current ?: return
        viewModelScope.launch {
            val prev = studyDao.card(card.id) ?: FlashcardStateEntity(caseId = card.id)
            val q = quality.coerceIn(0, 5)
            val ef = max(1.3, prev.easeFactor + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)))
            val now = System.currentTimeMillis()
            val next = if (q < 3) {
                prev.copy(easeFactor = ef, repetitions = 0, intervalDays = 0, lapses = prev.lapses + 1, dueAt = now + 10 * 60_000L, lastReviewedAt = now)
            } else {
                val reps = prev.repetitions + 1
                val interval = when (reps) { 1 -> 1; 2 -> 6; else -> (prev.intervalDays * ef).roundToInt().coerceAtLeast(1) }
                val bonus = if (q == 5) 1.3 else 1.0
                val days = (interval * bonus).roundToInt()
                prev.copy(easeFactor = ef, repetitions = reps, intervalDays = days, dueAt = now + days * 86_400_000L, lastReviewedAt = now)
            }
            studyDao.upsertCard(next)
            val requeue = if (q < 3) f.queue + card else f.queue
            _flash.value = f.copy(queue = requeue, position = f.position + 1, flipped = false, reviewed = f.reviewed + 1, again = f.again + if (q < 3) 1 else 0)
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        tts.stop()
    }
}
