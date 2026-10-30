package com.polylingua.edge.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.domain.model.SpeakingAssessment
import com.polylingua.edge.domain.model.SpeechSample
import com.polylingua.edge.domain.model.TaskContext
import com.polylingua.edge.dsp.PcmUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SavedSession(val id: Long, val stem: String, val json: File, val csv: File, val wav: File?)

/**
 * saved_results/ vault: every assessment is written as
 *   "IELTS_Speaking_Tech_test N.json" (full diagnostic), ".csv" (metrics) and ".wav" (16 kHz take)
 * with N auto-incremented, and indexed in Room for the history tab. Any JSON can be re-opened to
 * hydrate the Bento diagnostic dashboard.
 */
@Singleton
class SessionArtifactStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: PolyLinguaDatabase,
) {
    companion object {
        const val PREFIX = "IELTS_Speaking_Tech_test "
        private val STEM_RE = Regex("""^IELTS_Speaking_Tech_test (\d+)\.(json|csv|wav)$""")
    }

    private val mutex = Mutex()
    val dir: File get() = File(context.filesDir, "saved_results").apply { mkdirs() }

    val history: Flow<List<SessionResultEntity>> = db.sessionDao().all()

    /** Next free stem: "IELTS_Speaking_Tech_test 1", "... 2", ... */
    fun nextStem(): String {
        val max = dir.listFiles()?.mapNotNull { STEM_RE.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }?.maxOrNull() ?: 0
        return PREFIX + (max + 1)
    }

    suspend fun save(
        context: TaskContext,
        assessment: SpeakingAssessment,
        sample: SpeechSample,
        engine: String,
        audio: ShortArray,
        caseId: Long?,
    ): SavedSession = mutex.withLock {
        withContext(CoreExecutors.dsp) {
            val stem = nextStem()
            val now = System.currentTimeMillis()
            val wav = if (audio.isNotEmpty()) File(dir, "$stem.wav").also { PcmUtils.writeWav(it, audio, 16000) } else null
            val csv = File(dir, "$stem.csv").apply { writeText(AssessmentJson.csv(stem, context, assessment, engine)) }
            val json = File(dir, "$stem.json")
            json.writeText(AssessmentJson.encode(stem, now, context, assessment, sample, engine, wav?.name ?: "", csv.name).toString(2))
            val id = db.sessionDao().insert(
                SessionResultEntity(
                    createdAt = now, stem = stem, module = context.module.name, prompt = context.prompt, topic = context.topic,
                    overallBand = assessment.overallBand, fc = assessment.fc.band, lr = assessment.lr.band,
                    gra = assessment.gra.band, p = assessment.p.band, cefr = assessment.cefr.name,
                    wpm = assessment.fluency.wpm, durationSec = sample.totalDurationSec, wordCount = assessment.fluency.wordCount,
                    jsonPath = json.absolutePath, csvPath = csv.absolutePath, wavPath = wav?.absolutePath ?: "",
                    caseId = caseId, transcriptEngine = engine,
                ),
            )
            SavedSession(id, stem, json, csv, wav)
        }
    }

    /**
     * Rewrites an existing session's JSON/CSV (e.g. after the offline-AI grammar review) and its
     * Room row, keeping the same "IELTS_Speaking_Tech_test N" stem and WAV.
     */
    suspend fun overwrite(
        sessionId: Long,
        stem: String,
        context: TaskContext,
        assessment: SpeakingAssessment,
        sample: SpeechSample,
        engine: String,
    ) = mutex.withLock {
        withContext(CoreExecutors.dsp) {
            val entity = db.sessionDao().byId(sessionId) ?: return@withContext
            val wav = File(dir, "$stem.wav").takeIf { it.isFile }
            val csv = File(dir, "$stem.csv").apply { writeText(AssessmentJson.csv(stem, context, assessment, engine)) }
            File(dir, "$stem.json").writeText(
                AssessmentJson.encode(stem, entity.createdAt, context, assessment, sample, engine, wav?.name ?: "", csv.name).toString(2),
            )
            db.sessionDao().update(
                entity.copy(
                    overallBand = assessment.overallBand, fc = assessment.fc.band, lr = assessment.lr.band,
                    gra = assessment.gra.band, p = assessment.p.band, cefr = assessment.cefr.name,
                ),
            )
        }
    }

    /** Re-reads a stored JSON (from the vault or an external file) for the dashboard. */
    suspend fun load(file: File): StoredSession = withContext(CoreExecutors.dsp) {
        AssessmentJson.decode(JSONObject(file.readText()))
    }

    suspend fun load(uri: Uri): StoredSession = withContext(CoreExecutors.dsp) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Cannot open the selected file")
        AssessmentJson.decode(JSONObject(text))
    }

    suspend fun loadAudio(stored: StoredSession): FloatArray? = withContext(CoreExecutors.dsp) {
        val f = File(dir, stored.wavFile)
        if (stored.wavFile.isBlank() || !f.isFile) null else f.inputStream().use { PcmUtils.readWav(it).samples }
    }

    suspend fun delete(entity: SessionResultEntity) = withContext(CoreExecutors.dsp) {
        listOf(entity.jsonPath, entity.csvPath, entity.wavPath).filter { it.isNotBlank() }.forEach { File(it).delete() }
        db.sessionDao().delete(entity.id)
    }

    /** Share sheet for a session's files via the FileProvider ("saved_results/" path). */
    fun shareIntent(entity: SessionResultEntity): Intent {
        val authority = "${context.packageName}.fileprovider"
        val uris = ArrayList<Uri>()
        listOf(entity.jsonPath, entity.csvPath, entity.wavPath).filter { it.isNotBlank() && File(it).isFile }.forEach {
            uris += FileProvider.getUriForFile(context, authority, File(it))
        }
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, entity.stem)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
