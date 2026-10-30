package com.polylingua.edge.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.polylingua.edge.domain.model.CefrLevel
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.domain.model.PracticeCase
import kotlinx.coroutines.flow.Flow

// ============================================================================ entities

/** A practice package = one worksheet of an imported workbook, or one built-in module sheet. */
@Entity(tableName = "case_packages", indices = [Index("builtIn")])
data class PackageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceFile: String,
    val sheetName: String,
    val importedAt: Long,
    val caseCount: Int,
    val builtIn: Boolean,
    val headerDetected: Boolean,
    val warnings: String = "",
)

@Entity(
    tableName = "practice_cases",
    indices = [Index("packageId"), Index(value = ["module", "cefr"]), Index("topic")],
)
data class CaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageId: Long,
    val sheetName: String,
    val number: Int,
    val prompt: String,
    val colC: String,
    val colD: String,
    val colE: String,
    val colF: String,
    val colG: String,
    val colH: String,
    val colI: String,
    val colJ: String,
    val colK: String,
    val cefr: String,
    val targetBand: Double,
    val module: String,
    val prepSec: Int,
    val speakSec: Int,
    val topic: String,
    val translationId: String,
) {
    fun toModel(): PracticeCase = PracticeCase(
        id = id, sheetName = sheetName, number = number, prompt = prompt,
        colC = colC, colD = colD, colE = colE, colF = colF, colG = colG,
        colH = colH, colI = colI, colJ = colJ, colK = colK,
        cefr = CefrLevel.parse(cefr) ?: CefrLevel.B1,
        targetBand = targetBand,
        module = ModuleType.parse(module) ?: ModuleType.STIMULER_PART1,
        prepSec = prepSec, speakSec = speakSec, topic = topic, translationId = translationId,
    )

    companion object {
        fun from(c: PracticeCase, packageId: Long): CaseEntity = CaseEntity(
            packageId = packageId, sheetName = c.sheetName, number = c.number, prompt = c.prompt,
            colC = c.colC, colD = c.colD, colE = c.colE, colF = c.colF, colG = c.colG,
            colH = c.colH, colI = c.colI, colJ = c.colJ, colK = c.colK,
            cefr = c.cefr.name, targetBand = c.targetBand, module = c.module.name,
            prepSec = c.prepSec, speakSec = c.speakSec, topic = c.topic, translationId = c.translationId,
        )
    }
}

/** One saved speaking assessment (files live in saved_results/). */
@Entity(tableName = "session_results", indices = [Index("createdAt")])
data class SessionResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val stem: String,
    val module: String,
    val prompt: String,
    val topic: String,
    val overallBand: Double,
    val fc: Double,
    val lr: Double,
    val gra: Double,
    val p: Double,
    val cefr: String,
    val wpm: Double,
    val durationSec: Double,
    val wordCount: Int,
    val jsonPath: String,
    val csvPath: String,
    val wavPath: String,
    val caseId: Long?,
    val transcriptEngine: String,
)

@Entity(tableName = "quiz_attempts", indices = [Index("caseId"), Index("sessionTag")])
data class QuizAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseId: Long,
    val sessionTag: String,
    val chosenIndex: Int,
    val correct: Boolean,
    val elapsedMs: Long,
    val flagged: Boolean,
    val attemptedAt: Long,
)

/** SM-2 spaced-repetition state per flashcard. */
@Entity(tableName = "flashcard_state")
data class FlashcardStateEntity(
    @PrimaryKey val caseId: Long,
    val easeFactor: Double = 2.5,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    val lapses: Int = 0,
    val dueAt: Long = 0,
    val lastReviewedAt: Long = 0,
)

/** Local video/audio imported into the Cake shadowing studio. */
@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val title: String,
    val mimeType: String,
    val subtitleUri: String?,
    val translationUri: String?,
    val durationMs: Long,
    val addedAt: Long,
    val lastPositionMs: Long = 0,
)

@Entity(tableName = "chat_messages", indices = [Index("personaId")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personaId: String,
    val role: String,
    val text: String,
    val correctedText: String = "",
    val correctionNote: String = "",
    val createdAt: Long,
)

@Entity(tableName = "shadowing_attempts", indices = [Index("createdAt")])
data class ShadowingAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceKey: String,
    val sentence: String,
    val tempo: Double,
    val mastery: Double,
    val phonemeAccuracy: Double,
    val intonation: Double,
    val rhythm: Double,
    val createdAt: Long,
)

/** Running per-phoneme accuracy for the "weakest sounds" panel. */
@Entity(tableName = "phoneme_stats")
data class PhonemeStatEntity(
    @PrimaryKey val arpabet: String,
    val attempts: Int,
    val meanScore: Double,
    val lastScore: Int,
    val updatedAt: Long,
)

// ================================================================================ DAOs

@Dao
abstract class CaseDao {
    @Insert
    abstract suspend fun insertPackage(p: PackageEntity): Long

    @Query("UPDATE case_packages SET caseCount = :count, warnings = :warnings WHERE id = :id")
    abstract suspend fun updatePackageCount(id: Long, count: Int, warnings: String)

    @Insert
    abstract suspend fun insertCases(cases: List<CaseEntity>)

    @Query("SELECT * FROM case_packages ORDER BY builtIn DESC, importedAt DESC, id ASC")
    abstract fun packages(): Flow<List<PackageEntity>>

    @Query("SELECT COUNT(*) FROM case_packages WHERE builtIn = 1")
    abstract suspend fun builtInPackageCount(): Int

    @Query("SELECT COUNT(*) FROM practice_cases")
    abstract fun caseCount(): Flow<Int>

    @Query("SELECT * FROM practice_cases WHERE packageId = :packageId ORDER BY number")
    abstract suspend fun casesInPackage(packageId: Long): List<CaseEntity>

    @Query("SELECT * FROM practice_cases WHERE packageId IN (:packageIds) ORDER BY packageId, number")
    abstract suspend fun casesInPackages(packageIds: List<Long>): List<CaseEntity>

    @Query("SELECT * FROM practice_cases WHERE module = :module AND (:cefr = '' OR cefr = :cefr) ORDER BY RANDOM() LIMIT :limit")
    abstract suspend fun randomCases(module: String, cefr: String, limit: Int): List<CaseEntity>

    @Query("SELECT * FROM practice_cases WHERE module IN (:modules) AND (:cefr = '' OR cefr = :cefr) AND (:topic = '' OR topic = :topic) ORDER BY RANDOM() LIMIT :limit")
    abstract suspend fun randomCasesIn(modules: List<String>, cefr: String, topic: String, limit: Int): List<CaseEntity>

    @Query("SELECT * FROM practice_cases WHERE id = :id")
    abstract suspend fun case(id: Long): CaseEntity?

    @Query("SELECT DISTINCT topic FROM practice_cases WHERE topic != '' ORDER BY topic")
    abstract suspend fun topics(): List<String>

    @Query("SELECT module AS module, COUNT(*) AS count FROM practice_cases GROUP BY module")
    abstract suspend fun moduleCounts(): List<ModuleCount>

    @Query("DELETE FROM practice_cases WHERE packageId = :packageId")
    abstract suspend fun deleteCasesOf(packageId: Long)

    @Query("DELETE FROM case_packages WHERE id = :packageId")
    abstract suspend fun deletePackageRow(packageId: Long)

    @Transaction
    open suspend fun deletePackage(packageId: Long) {
        deleteCasesOf(packageId)
        deletePackageRow(packageId)
    }
}

data class ModuleCount(val module: String, val count: Int)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(s: SessionResultEntity): Long

    @Query("SELECT * FROM session_results ORDER BY createdAt DESC")
    fun all(): Flow<List<SessionResultEntity>>

    @Query("SELECT * FROM session_results WHERE id = :id")
    suspend fun byId(id: Long): SessionResultEntity?

    @Query("DELETE FROM session_results WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM session_results ORDER BY createdAt DESC LIMIT :n")
    suspend fun recent(n: Int): List<SessionResultEntity>
}

@Dao
interface StudyDao {
    @Insert
    suspend fun insertAttempt(a: QuizAttemptEntity): Long

    @Query("SELECT * FROM quiz_attempts WHERE sessionTag = :tag ORDER BY attemptedAt")
    suspend fun attemptsFor(tag: String): List<QuizAttemptEntity>

    @Query("SELECT COUNT(*) FROM quiz_attempts WHERE correct = 1")
    fun correctCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM quiz_attempts")
    fun attemptCount(): Flow<Int>

    @Upsert
    suspend fun upsertCard(s: FlashcardStateEntity)

    @Query("SELECT * FROM flashcard_state WHERE caseId = :caseId")
    suspend fun card(caseId: Long): FlashcardStateEntity?

    @Query("SELECT * FROM flashcard_state WHERE caseId IN (:ids)")
    suspend fun cards(ids: List<Long>): List<FlashcardStateEntity>

    @Insert
    suspend fun insertShadowing(a: ShadowingAttemptEntity): Long

    @Query("SELECT * FROM shadowing_attempts ORDER BY createdAt DESC LIMIT :n")
    fun recentShadowing(n: Int): Flow<List<ShadowingAttemptEntity>>

    @Upsert
    suspend fun upsertPhoneme(p: PhonemeStatEntity)

    @Query("SELECT * FROM phoneme_stats WHERE arpabet = :arpabet")
    suspend fun phoneme(arpabet: String): PhonemeStatEntity?

    @Query("SELECT * FROM phoneme_stats WHERE attempts >= 3 ORDER BY meanScore ASC LIMIT :n")
    fun weakestPhonemes(n: Int): Flow<List<PhonemeStatEntity>>
}

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: MediaItemEntity): Long

    @Query("SELECT * FROM media_items ORDER BY addedAt DESC")
    fun all(): Flow<List<MediaItemEntity>>

    @Query("UPDATE media_items SET lastPositionMs = :pos WHERE id = :id")
    suspend fun savePosition(id: Long, pos: Long)

    @Query("UPDATE media_items SET subtitleUri = :sub, translationUri = :tr WHERE id = :id")
    suspend fun setSubtitles(id: Long, sub: String?, tr: String?)

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChatDao {
    @Insert
    suspend fun insert(m: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET correctedText = :corrected, correctionNote = :note WHERE id = :id")
    suspend fun setCorrection(id: Long, corrected: String, note: String)

    @Query("SELECT * FROM chat_messages WHERE personaId = :personaId ORDER BY createdAt ASC, id ASC")
    fun thread(personaId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE personaId = :personaId ORDER BY createdAt DESC, id DESC LIMIT :n")
    suspend fun lastMessages(personaId: String, n: Int): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE personaId = :personaId")
    suspend fun clear(personaId: String)
}

// ============================================================================ database

@Database(
    entities = [
        PackageEntity::class, CaseEntity::class, SessionResultEntity::class, QuizAttemptEntity::class,
        FlashcardStateEntity::class, MediaItemEntity::class, ChatMessageEntity::class,
        ShadowingAttemptEntity::class, PhonemeStatEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PolyLinguaDatabase : RoomDatabase() {
    abstract fun caseDao(): CaseDao
    abstract fun sessionDao(): SessionDao
    abstract fun studyDao(): StudyDao
    abstract fun mediaDao(): MediaDao
    abstract fun chatDao(): ChatDao

    companion object {
        const val NAME = "polylingua.db"
    }
}
