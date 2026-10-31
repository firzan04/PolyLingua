package com.polylingua.edge.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.polylingua.edge.PolyLinguaForegroundService
import com.polylingua.edge.core.AppSettings
import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.core.LinguisticResources
import com.polylingua.edge.data.CaseRepository
import com.polylingua.edge.data.MediaItemEntity
import com.polylingua.edge.data.PolyLinguaDatabase
import com.polylingua.edge.data.ShadowingAttemptEntity
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.domain.model.PracticeCase
import com.polylingua.edge.dsp.PcmUtils
import com.polylingua.edge.dsp.TarsosWsolaTimeStretcher
import com.polylingua.edge.media.MediaPcmExtractor
import com.polylingua.edge.media.MediaUtils
import com.polylingua.edge.media.SubtitleCue
import com.polylingua.edge.media.SubtitleParser
import com.polylingua.edge.speech.AssessmentPipeline
import com.polylingua.edge.speech.AudioClip
import com.polylingua.edge.speech.LiveCaptureState
import com.polylingua.edge.speech.ShadowingOutcome
import com.polylingua.edge.speech.SpeakingCaptureController
import com.polylingua.edge.speech.SpeechSynthesisEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A shadowing line with dual subtitles (English + IPA + Bahasa Indonesia). */
data class ShadowCue(val index: Int, val startMs: Long, val endMs: Long, val text: String, val ipa: String, val translation: String)

data class CakeUiState(
    val source: Int = 0,                 // 0 built-in sentences, 1 local media
    val sentences: List<PracticeCase> = emptyList(),
    val sentenceIndex: Int = 0,
    val media: MediaItemEntity? = null,
    val cues: List<ShadowCue> = emptyList(),
    val cueIndex: Int = 0,
    val speed: Float = 1.0f,
    val loopA: Long? = null,
    val loopB: Long? = null,
    val loopOn: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val recording: Boolean = false,
    val analysing: Boolean = false,
    val outcome: ShadowingOutcome? = null,
    val message: String? = null,
    val showIpa: Boolean = true,
    val showTranslation: Boolean = true,
    /** 0..1 while an imported video/audio is being auto-transcribed offline. */
    val transcribing: Float? = null,
    /** "Listen…" / "Speak!" stage of the Shadow-This-Line sequence. */
    val shadowStage: String? = null,
    /** 3-2-1 pulse before recording. */
    val countdown: Int? = null,
    /** Loudness envelopes for the waveform overlay (native vs learner), 0..1. */
    val refEnvelope: FloatArray? = null,
    val userEnvelope: FloatArray? = null,
) {
    val currentCue: ShadowCue? get() = cues.getOrNull(cueIndex)
}

/**
 * Cake-style shadowing studio: built-in sentences voiced by neural TTS (slowed with WSOLA) or the
 * learner's own videos/audio (ExoPlayer, pitch-preserving speed) with dual subtitles, A-B loops
 * and a shadow recorder scored 40 % phonetic accuracy / 35 % intonation / 25 % rhythm.
 */
@HiltViewModel
class CakeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CaseRepository,
    private val tts: SpeechSynthesisEngine,
    private val capture: SpeakingCaptureController,
    private val pipeline: AssessmentPipeline,
    private val resources: LinguisticResources,
    private val settings: AppSettings,
    private val asr: com.polylingua.edge.speech.SpeechRecognitionEngine,
    net: com.polylingua.edge.network.ConnectivityAndWebRtcManager,
    db: PolyLinguaDatabase,
) : ViewModel() {
    val network = net.network

    companion object {
        val SPEEDS = listOf(0.60f, 0.75f, 0.85f, 1.00f, 1.25f)
    }

    private val mediaDao = db.mediaDao()
    private val studyDao = db.studyDao()

    val mediaLibrary: StateFlow<List<MediaItemEntity>> = mediaDao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recentAttempts: StateFlow<List<ShadowingAttemptEntity>> = studyDao.recentShadowing(12).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _state = MutableStateFlow(CakeUiState())
    val state: StateFlow<CakeUiState> = _state
    private val _live = MutableStateFlow(LiveCaptureState())
    val live: StateFlow<LiveCaptureState> = _live

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
                if (isPlaying) PolyLinguaForegroundService.acquire(context, PolyLinguaForegroundService.Reason.PLAYBACK, "Shadowing studio")
                else PolyLinguaForegroundService.release(context, PolyLinguaForegroundService.Reason.PLAYBACK)
            }
        })
    }

    private var clip: AudioClip? = null
    private var clipKey = ""
    private var ticker: Job? = null
    private var active: SpeakingCaptureController.ActiveCapture? = null
    private var liveJob: Job? = null
    private var autoStop: Job? = null
    private var translationCues: List<SubtitleCue> = emptyList()

    init {
        viewModelScope.launch {
            repository.ensureBuiltInSeeded()
            loadSentences()
        }
        viewModelScope.launch { capture.preload() }
        ticker = viewModelScope.launch {
            while (isActive) {
                tick()
                delay(50)
            }
        }
    }

    private suspend fun loadSentences() {
        val level = settings.settings.value.targetCefr
        val list = (repository.randomCases(ModuleType.CAKE_SHADOWING, level, 20) + repository.randomCases(ModuleType.CAKE_SHADOWING, null, 10))
            .distinctBy { it.id }
        _state.value = _state.value.copy(sentences = list, sentenceIndex = 0)
        if (_state.value.source == 0) selectSentence(0)
    }

    fun setSource(s: Int) {
        stopPlayback()
        _state.value = _state.value.copy(source = s, outcome = null, cues = emptyList(), cueIndex = 0, loopA = null, loopB = null, loopOn = false)
        if (s == 0) selectSentence(_state.value.sentenceIndex) else _state.value.media?.let { openMedia(it) }
    }

    // --------------------------------------------------------------- built-in sentences

    fun selectSentence(i: Int) {
        val c = _state.value.sentences.getOrNull(i) ?: return
        stopPlayback()
        viewModelScope.launch {
            val ipa = withContext(CoreExecutors.dsp) { resources.ipaOfSentence(c.referenceTranscript) }
            clip = null
            _state.value = _state.value.copy(
                sentenceIndex = i, outcome = null, cueIndex = 0, loopA = null, loopB = null, loopOn = false,
                cues = listOf(ShadowCue(0, 0, 0, c.referenceTranscript, ipa, c.translationId)),
            )
        }
    }

    fun nextSentence() = selectSentence((_state.value.sentenceIndex + 1) % _state.value.sentences.size.coerceAtLeast(1))
    fun prevSentence() = selectSentence((_state.value.sentenceIndex - 1).coerceAtLeast(0))

    private suspend fun ensureClip(): AudioClip? {
        val cue = _state.value.currentCue ?: return null
        val voice = tts.defaultVoice()
        val key = "${cue.text}|${voice.id}"
        if (clip == null || clipKey != key) {
            clip = tts.synthesize(cue.text, voice, 1.0)
            clipKey = key
            clip?.let { c -> _state.value = _state.value.copy(durationMs = (c.durationSec * 1000).toLong()) }
        }
        return clip
    }

    // ------------------------------------------------------------------ local media

    fun importMedia(uri: Uri) {
        viewModelScope.launch {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val entity = withContext(CoreExecutors.dsp) {
                MediaItemEntity(
                    uri = uri.toString(),
                    title = MediaUtils.displayName(context, uri),
                    mimeType = context.contentResolver.getType(uri) ?: "video/*",
                    subtitleUri = null, translationUri = null,
                    durationMs = MediaUtils.durationMs(context, uri),
                    addedAt = System.currentTimeMillis(),
                )
            }
            val id = mediaDao.insert(entity)
            openMedia(entity.copy(id = id))
        }
    }

    fun attachSubtitles(uri: Uri, translation: Boolean) {
        val m = _state.value.media ?: return
        viewModelScope.launch {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val updated = if (translation) m.copy(translationUri = uri.toString()) else m.copy(subtitleUri = uri.toString())
            mediaDao.setSubtitles(m.id, updated.subtitleUri, updated.translationUri)
            openMedia(updated)
        }
    }

    fun deleteMedia(m: MediaItemEntity) {
        viewModelScope.launch {
            if (_state.value.media?.id == m.id) {
                player.stop()
                _state.value = _state.value.copy(media = null, cues = emptyList())
            }
            mediaDao.delete(m.id)
        }
    }

    fun openMedia(m: MediaItemEntity) {
        viewModelScope.launch {
            val cues = withContext(CoreExecutors.dsp) {
                val en = m.subtitleUri?.let { runCatching { SubtitleParser.parse(MediaUtils.readText(context, Uri.parse(it))) }.getOrDefault(emptyList()) }.orEmpty()
                translationCues = m.translationUri?.let { runCatching { SubtitleParser.parse(MediaUtils.readText(context, Uri.parse(it))) }.getOrDefault(emptyList()) }.orEmpty()
                en.map { c ->
                    ShadowCue(c.index, c.startMs, c.endMs, c.text, resources.ipaOfSentence(c.text),
                        SubtitleParser.bestOverlap(translationCues, c.startMs, c.endMs)?.text.orEmpty())
                }
            }
            player.setMediaItem(MediaItem.fromUri(Uri.parse(m.uri)))
            player.prepare()
            if (m.lastPositionMs > 0) player.seekTo(m.lastPositionMs)
            player.playbackParameters = PlaybackParameters(_state.value.speed)
            _state.value = _state.value.copy(source = 1, media = m, cues = cues, cueIndex = 0, outcome = null, durationMs = m.durationMs,
                refEnvelope = null, userEnvelope = null,
                message = if (cues.isEmpty()) "No subtitles yet: tap \"Auto-transcribe offline\" to create English lines from the audio, or attach an .srt/.vtt file." else null)
        }
    }

    /**
     * Offline auto-subtitles for an imported video/audio: decode the audio track (<= 20 min),
     * find speech with VAD, transcribe each line with the final-pass recogniser, save an .srt and
     * open it as a Cake lesson (IPA per line; pitch is extracted per line when you shadow it).
     */
    fun autoTranscribe() {
        val m = _state.value.media ?: return
        if (_state.value.transcribing != null) return
        stopPlayback()
        viewModelScope.launch {
            _state.value = _state.value.copy(transcribing = 0f, message = null)
            PolyLinguaForegroundService.acquire(context, PolyLinguaForegroundService.Reason.INDEXING, "Transcribing ${m.title}")
            try {
                val pcm = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    MediaPcmExtractor.extractAll(context, Uri.parse(m.uri), maxSec = 20 * 60) { p -> _state.value = _state.value.copy(transcribing = p * 0.2f) }
                } ?: error("this file has no readable audio track")
                val lines = asr.transcribeLong(pcm) { p -> _state.value = _state.value.copy(transcribing = 0.2f + p * 0.8f) }
                if (lines.isEmpty()) error("no speech was found")
                val file = java.io.File(context.filesDir, "subtitles/media_${m.id}.srt")
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.writeText(com.polylingua.edge.media.SubtitleWriter.toSrt(lines))
                }
                val uri = Uri.fromFile(file).toString()
                mediaDao.setSubtitles(m.id, uri, m.translationUri)
                openMedia(m.copy(subtitleUri = uri))
                com.polylingua.edge.core.UserNotices.post(
                    "Created ${lines.size} English lines offline${if (pcm.size >= 16000 * 20 * 60) " (first 20 minutes)" else ""}.",
                    com.polylingua.edge.core.UserNotices.Kind.SUCCESS,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = _state.value.copy(message = "Auto-transcription failed: ${t.message}")
            } finally {
                PolyLinguaForegroundService.release(context, PolyLinguaForegroundService.Reason.INDEXING)
                _state.value = _state.value.copy(transcribing = null)
            }
        }
    }

    private var shadowJob: Job? = null

    /**
     * Cake-style "Shadow This Line": plays the native line, shows a 3-2-1 pulse, then records the
     * learner and overlays their waveform and pitch on the native actor's.
     */
    fun shadowThisLine() {
        val st = _state.value
        if (st.recording || st.analysing || shadowJob?.isActive == true) return
        val cue = st.currentCue ?: return
        stoppedByUser = false
        shadowJob = viewModelScope.launch {
            _state.value = _state.value.copy(shadowStage = "Listen…", outcome = null, refEnvelope = null, userEnvelope = null)
            if (st.source == 1 && st.media != null) {
                player.playbackParameters = PlaybackParameters(st.speed)
                player.seekTo(cue.startMs)
                player.play()
                val maxWait = ((cue.endMs - cue.startMs) / st.speed).toLong() + 2500L
                kotlinx.coroutines.withTimeoutOrNull(maxWait) {
                    while (isActive && player.currentPosition < cue.endMs) delay(25)
                }
                player.pause()
            } else {
                val base = ensureClip()
                if (base == null) {
                    _state.value = _state.value.copy(shadowStage = null, message = "No voice available - install a neural voice or enable Android TTS.")
                    return@launch
                }
                val sp = st.speed.toDouble()
                val c = if (sp == 1.0) base else withContext(CoreExecutors.dsp) {
                    AudioClip(TarsosWsolaTimeStretcher.stretch(base.samples, base.sampleRate, sp), base.sampleRate)
                }
                tts.player.play(c, "Shadowing model")
            }
            if (stoppedByUser) {
                _state.value = _state.value.copy(shadowStage = null)
                return@launch
            }
            for (n in 3 downTo 1) {
                _state.value = _state.value.copy(shadowStage = null, countdown = n)
                delay(650)
            }
            _state.value = _state.value.copy(countdown = null, shadowStage = "Speak!")
            startShadow()
        }
    }

    fun cancelShadowSequence() {
        shadowJob?.cancel()
        stopPlayback()
        _state.value = _state.value.copy(shadowStage = null, countdown = null)
    }

    // --------------------------------------------------------------------- transport

    fun setSpeed(s: Float) {
        _state.value = _state.value.copy(speed = s)
        player.playbackParameters = PlaybackParameters(s)
    }

    fun togglePlay() {
        val st = _state.value
        if (st.source == 1) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        if (tts.player.state.value.playing) {
            stopPlayback()
            return
        }
        stoppedByUser = false
        viewModelScope.launch {
            val base = ensureClip() ?: run {
                _state.value = _state.value.copy(message = "No voice available - install a neural voice or enable Android TTS.")
                return@launch
            }
            do {
                val sp = _state.value.speed.toDouble()
                val c = if (sp == 1.0) base else withContext(CoreExecutors.dsp) {
                    AudioClip(TarsosWsolaTimeStretcher.stretch(base.samples, base.sampleRate, sp), base.sampleRate)
                }
                val a = (_state.value.loopA ?: 0L) / 1000.0 / sp
                val b = (_state.value.loopB?.let { it / 1000.0 / sp }) ?: Double.MAX_VALUE
                tts.player.play(c, "Shadowing model", startSec = if (_state.value.loopOn) a else 0.0, endSec = if (_state.value.loopOn) b else Double.MAX_VALUE)
            } while (_state.value.loopOn && !_state.value.recording && isActive && !stoppedByUser)
            stoppedByUser = false
        }
    }

    private var stoppedByUser = false

    private fun stopPlayback() {
        stoppedByUser = true
        tts.stop()
        if (player.isPlaying) player.pause()
    }

    fun seekTo(ms: Long) {
        if (_state.value.source == 1) player.seekTo(ms)
    }

    fun selectCue(i: Int) {
        val cue = _state.value.cues.getOrNull(i) ?: return
        _state.value = _state.value.copy(cueIndex = i, outcome = null)
        if (_state.value.source == 1) {
            _state.value = _state.value.copy(loopA = cue.startMs, loopB = cue.endMs)
            player.seekTo(cue.startMs)
        }
    }

    fun markA() {
        val pos = currentPositionMs()
        _state.value = _state.value.copy(loopA = pos, loopB = _state.value.loopB?.takeIf { it > pos })
    }

    fun markB() {
        val pos = currentPositionMs()
        val a = _state.value.loopA ?: 0L
        if (pos > a) _state.value = _state.value.copy(loopB = pos, loopOn = true)
    }

    fun toggleLoop() {
        val st = _state.value
        _state.value = st.copy(loopOn = !st.loopOn && st.loopB != null)
    }

    fun clearLoop() { _state.value = _state.value.copy(loopA = null, loopB = null, loopOn = false) }

    fun toggleIpa() { _state.value = _state.value.copy(showIpa = !_state.value.showIpa) }
    fun toggleTranslation() { _state.value = _state.value.copy(showTranslation = !_state.value.showTranslation) }

    private fun currentPositionMs(): Long = if (_state.value.source == 1) player.currentPosition else
        (tts.player.state.value.positionSec * 1000 * _state.value.speed).toLong()

    private var tickCount = 0

    private fun tick() {
        val st = _state.value
        if (st.source == 1 && st.media != null) {
            val pos = player.currentPosition
            if (++tickCount % 100 == 0 && player.isPlaying) {
                val id = st.media.id
                viewModelScope.launch { runCatching { mediaDao.savePosition(id, pos) } }
            }
            val dur = player.duration.takeIf { it > 0 } ?: st.durationMs
            if (st.loopOn && st.loopB != null && pos >= st.loopB) player.seekTo(st.loopA ?: 0L)
            val cueIdx = st.cues.indexOfFirst { pos in it.startMs..it.endMs }.takeIf { it >= 0 } ?: st.cueIndex
            if (pos != st.positionMs || cueIdx != st.cueIndex || dur != st.durationMs) {
                _state.value = st.copy(positionMs = pos, durationMs = dur, cueIndex = if (st.recording || st.analysing) st.cueIndex else cueIdx)
            }
        } else if (st.source == 0) {
            val ps = tts.player.state.value
            val pos = (ps.positionSec * 1000 * st.speed).toLong()
            if (pos != st.positionMs || ps.playing != st.playing) _state.value = st.copy(positionMs = pos, playing = ps.playing)
        }
    }

    // ---------------------------------------------------------------- shadow recorder

    fun toggleShadow() {
        if (_state.value.recording) finishShadow() else startShadow()
    }

    private fun startShadow() {
        val cue = _state.value.currentCue ?: return
        stopPlayback()
        viewModelScope.launch {
            val cap = capture.begin(viewModelScope, "Shadowing: ${cue.text.take(30)}") ?: run {
                _state.value = _state.value.copy(message = "Microphone or offline speech pack unavailable.")
                return@launch
            }
            active = cap
            liveJob = viewModelScope.launch { cap.live.collect { _live.value = it } }
            _state.value = _state.value.copy(recording = true, outcome = null, message = null)
            val refMs = if (_state.value.source == 1) cue.endMs - cue.startMs else (ensureClip()?.durationSec?.times(1000))?.toLong() ?: 4000L
            autoStop = viewModelScope.launch {
                delay((refMs * 1.6 / _state.value.speed).toLong() + 1500L)
                if (_state.value.recording) finishShadow()
            }
        }
    }

    private fun finishShadow() {
        val cap = active ?: return
        active = null
        autoStop?.cancel()
        val st = _state.value
        val cue = st.currentCue ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(recording = false, analysing = true)
            val captured = cap.end()
            liveJob?.cancel()
            val reference = if (st.source == 1 && st.media != null) {
                withContext(CoreExecutors.dsp) { MediaPcmExtractor.extract(context, Uri.parse(st.media.uri), cue.startMs, cue.endMs) }
            } else ensureClip()
            val outcome = runCatching { pipeline.assessShadowing(captured, cue.text, reference) }.getOrNull()
            val (refEnv, userEnv) = withContext(CoreExecutors.dsp) {
                reference?.let { PcmUtils.speechEnvelope(it.samples, it.sampleRate) } to
                    captured.audio.takeIf { it.isNotEmpty() }?.let { PcmUtils.speechEnvelope(PcmUtils.shortsToFloats(it), 16000) }
            }
            if (outcome != null) {
                studyDao.insertShadowing(
                    ShadowingAttemptEntity(
                        sourceKey = if (st.source == 1) "media:${st.media?.id}:${cue.index}" else "case:${st.sentences.getOrNull(st.sentenceIndex)?.id}",
                        sentence = cue.text, tempo = st.speed.toDouble(), mastery = outcome.score.mastery,
                        phonemeAccuracy = outcome.score.phonemeAccuracy, intonation = outcome.score.intonationSimilarity,
                        rhythm = outcome.score.rhythmSync, createdAt = System.currentTimeMillis(),
                    ),
                )
            }
            _state.value = _state.value.copy(analysing = false, outcome = outcome, shadowStage = null,
                refEnvelope = refEnv, userEnvelope = userEnv,
                message = if (outcome == null) "Could not score this take - try again." else null)
        }
    }

    fun speakTranslationHint() {
        val cue = _state.value.currentCue ?: return
        if (cue.translation.isBlank()) return
        viewModelScope.launch { tts.speak(cue.translation, tts.resolve(listOf("system:id-ID")), label = "Terjemahan") }
    }

    fun consumeMessage() { _state.value = _state.value.copy(message = null) }

    override fun onCleared() {
        shadowJob?.cancel()
        ticker?.cancel()
        autoStop?.cancel()
        liveJob?.cancel()
        active?.abort()
        tts.stop()
        player.release()
        PolyLinguaForegroundService.release(context, PolyLinguaForegroundService.Reason.PLAYBACK)
    }
}
