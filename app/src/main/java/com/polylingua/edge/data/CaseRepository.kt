package com.polylingua.edge.data

import androidx.room.withTransaction
import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.core.LinguisticResources
import com.polylingua.edge.domain.model.CefrLevel
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.domain.model.PracticeCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** IELTS full mock: Part 1 interview, Part 2 cue card, Part 3 discussion on a shared topic. */
data class MockTestPlan(val topic: String, val part1: List<PracticeCase>, val part2: PracticeCase?, val part3: List<PracticeCase>) {
    val all: List<PracticeCase> get() = part1 + listOfNotNull(part2) + part3
}

@Singleton
class CaseRepository @Inject constructor(
    private val db: PolyLinguaDatabase,
    private val resources: LinguisticResources,
) {
    private val dao = db.caseDao()
    private val seedMutex = Mutex()

    val packages: Flow<List<PackageEntity>> = dao.packages()
    val caseCount: Flow<Int> = dao.caseCount()

    /** Generates the 1,020-case built-in curriculum into Room on first launch (idempotent). */
    suspend fun ensureBuiltInSeeded(): Int = seedMutex.withLock {
        if (dao.builtInPackageCount() > 0) return 0
        val cases = generateBuiltIn()
        val now = System.currentTimeMillis()
        db.withTransaction {
            for ((sheet, list) in cases.groupBy { it.sheetName }) {
                val id = dao.insertPackage(
                    PackageEntity(name = sheet, sourceFile = "Built-in curriculum", sheetName = sheet, importedAt = now,
                        caseCount = list.size, builtIn = true, headerDetected = true),
                )
                list.chunked(400).forEach { chunk -> dao.insertCases(chunk.map { CaseEntity.from(it, id) }) }
            }
        }
        cases.size
    }

    suspend fun generateBuiltIn(): List<PracticeCase> = withContext(CoreExecutors.dsp) {
        Procedural1000CaseSeeder(resources.cmu).generate()
    }

    suspend fun randomCases(module: ModuleType, level: CefrLevel?, limit: Int, topic: String = ""): List<PracticeCase> {
        val rows = if (topic.isBlank()) dao.randomCases(module.name, level?.name ?: "", limit)
        else dao.randomCasesIn(listOf(module.name), level?.name ?: "", topic, limit)
        if (rows.size >= limit || level == null) return rows.map { it.toModel() }
        // Not enough at that level (e.g. a small imported package): widen to all levels.
        val extra = dao.randomCases(module.name, "", limit - rows.size).filter { e -> rows.none { it.id == e.id } }
        return (rows + extra).map { it.toModel() }
    }

    suspend fun casesInPackages(ids: List<Long>): List<PracticeCase> =
        if (ids.isEmpty()) emptyList() else dao.casesInPackages(ids).map { it.toModel() }

    suspend fun caseById(id: Long): PracticeCase? = dao.caseById(id)?.toModel()

    suspend fun topics(): List<String> = dao.topics()

    suspend fun moduleCounts(): Map<ModuleType, Int> =
        dao.moduleCounts().mapNotNull { mc -> ModuleType.parse(mc.module)?.let { it to mc.count } }.toMap()

    suspend fun deletePackage(id: Long) = dao.deletePackage(id)

    /** Builds a full Part 1 -> Part 2 -> Part 3 mock on one topic at (or near) [level]. */
    suspend fun buildMockTest(level: CefrLevel?): MockTestPlan {
        val cue = randomCases(ModuleType.STIMULER_PART2_CUECARD, level, 1).firstOrNull()
        val topic = cue?.topic.orEmpty()
        val p1 = randomCases(ModuleType.STIMULER_PART1, level, 4, topic).ifEmpty { randomCases(ModuleType.STIMULER_PART1, level, 4) }
        val p3 = randomCases(ModuleType.STIMULER_PART3, level, 4, topic).ifEmpty { randomCases(ModuleType.STIMULER_PART3, level, 4) }
        return MockTestPlan(topic, p1, cue, p3)
    }
}
