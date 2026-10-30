package com.polylingua.edge.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.room.withTransaction
import com.polylingua.edge.PolyLinguaForegroundService
import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.core.LinguisticResources
import com.polylingua.edge.data.xlsx.CaseSchemaMapper
import com.polylingua.edge.data.xlsx.XlsxWorkbookReader
import com.polylingua.edge.data.xlsx.XlsxWorkbookWriter
import com.polylingua.edge.domain.model.ModuleType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class WorkbookPreview(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val sheets: List<XlsxWorkbookReader.SheetInfo>,
)

sealed interface ImportProgress {
    data class Running(val sheetIndex: Int, val sheetCount: Int, val sheetName: String, val casesSoFar: Int) : ImportProgress {
        val fraction: Float get() = if (sheetCount == 0) 0f else sheetIndex.toFloat() / sheetCount
    }
    data class Done(val packages: Int, val cases: Int, val warnings: List<String>) : ImportProgress
    data class Failed(val message: String) : ImportProgress
}

data class ExportResult(val uri: Uri, val displayPath: String, val rows: Int, val sheets: Int)

/**
 * "[+ Lampirkan File Master Excel (.xlsx)]": copies the SAF document into the cache, lists every
 * worksheet (up to 1,000 selectable packages), maps rows with the 11/16-column schema inference
 * in [CaseSchemaMapper], and caches them in Room with live progress. Also exports the built-in
 * 1,020-case curriculum to Documents/PolyLingua_Master_1000_Cases.xlsx.
 */
@Singleton
class ExcelDynamicIngestor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: PolyLinguaDatabase,
    private val resources: LinguisticResources,
    private val repository: CaseRepository,
) {
    companion object {
        const val MAX_SHEETS = 1000
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val EXPORT_NAME = "PolyLingua_Master_1000_Cases.xlsx"
        private const val TAG = "PolyLinguaExcel"
    }

    /** Copies [uri] into app cache and reads the sheet directory. */
    suspend fun inspect(uri: Uri): WorkbookPreview = withContext(CoreExecutors.dsp) {
        val resolver = context.contentResolver
        var name = "workbook.xlsx"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val dir = File(context.cacheDir, "imports").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val target = File(dir, "${System.currentTimeMillis()}_$safe")
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it, 256 * 1024) } }
            ?: throw IOException("Cannot open the selected file")
        val sheets = try {
            XlsxWorkbookReader.open(target).use { it.sheets }
        } catch (t: Throwable) {
            target.delete()
            throw IOException("\"$name\" is not a valid Excel workbook (.xlsx). Older .xls files must be re-saved as .xlsx.")
        }
        if (sheets.isEmpty()) throw IOException("The workbook has no worksheets")
        WorkbookPreview(target, name, if (size > 0) size else target.length(), sheets)
    }

    /** Imports the selected sheets (indices into [WorkbookPreview.sheets]); emits progress. */
    fun import(preview: WorkbookPreview, sheetIndices: List<Int>): Flow<ImportProgress> = flow {
        val selected = sheetIndices.distinct().sorted().take(MAX_SHEETS).mapNotNull { preview.sheets.getOrNull(it) }
        if (selected.isEmpty()) {
            emit(ImportProgress.Failed("No sheets selected"))
            return@flow
        }
        PolyLinguaForegroundService.acquire(context, PolyLinguaForegroundService.Reason.INDEXING, preview.displayName)
        val dao = db.caseDao()
        val warnings = ArrayList<String>()
        var total = 0
        var packages = 0
        try {
            XlsxWorkbookReader.open(preview.file).use { reader ->
                for ((i, sheet) in selected.withIndex()) {
                    emit(ImportProgress.Running(i, selected.size, sheet.name, total))
                    if (i % 10 == 0) {
                        PolyLinguaForegroundService.update(context, PolyLinguaForegroundService.Reason.INDEXING,
                            "${preview.displayName}: sheet ${i + 1}/${selected.size}")
                    }
                    val rows = ArrayList<Pair<Int, List<String>>>()
                    reader.readSheet(sheet) { rowIndex, cells -> rows += rowIndex to cells }
                    val mapped = CaseSchemaMapper.mapSheet(sheet.name, rows, resources.cefr)
                    if (mapped.cases.isEmpty()) {
                        warnings += "${sheet.name}: no usable rows" + (mapped.warnings.firstOrNull()?.let { " ($it)" } ?: "")
                        continue
                    }
                    db.withTransaction {
                        val id = dao.insertPackage(
                            PackageEntity(
                                name = "${preview.displayName.substringBeforeLast('.')} › ${sheet.name}",
                                sourceFile = preview.displayName,
                                sheetName = sheet.name,
                                importedAt = System.currentTimeMillis(),
                                caseCount = mapped.cases.size,
                                builtIn = false,
                                headerDetected = mapped.headerDetected,
                                warnings = mapped.warnings.take(5).joinToString("\n"),
                            ),
                        )
                        mapped.cases.chunked(400).forEach { chunk -> dao.insertCases(chunk.map { CaseEntity.from(it, id) }) }
                    }
                    packages++
                    total += mapped.cases.size
                    warnings += mapped.warnings.take(3).map { "${sheet.name}: $it" }
                }
            }
            emit(ImportProgress.Done(packages, total, warnings))
        } catch (t: Throwable) {
            Log.e(TAG, "Import failed", t)
            emit(ImportProgress.Failed(t.message ?: "Import failed"))
        } finally {
            PolyLinguaForegroundService.release(context, PolyLinguaForegroundService.Reason.INDEXING)
        }
    }.flowOn(CoreExecutors.dsp)

    /**
     * Writes the built-in curriculum (one sheet per module, 18 columns) to
     * Documents/PolyLingua_Master_1000_Cases.xlsx through MediaStore (no storage permission).
     */
    suspend fun exportBuiltInWorkbook(): ExportResult = withContext(CoreExecutors.dsp) {
        val cases = repository.generateBuiltIn()
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relPath = Environment.DIRECTORY_DOCUMENTS + "/"
        // Replace our own previous export (files from an earlier install are left untouched).
        runCatching {
            resolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relPath, EXPORT_NAME), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    runCatching { resolver.delete(Uri.withAppendedPath(collection, id.toString()), null, null) }
                }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, EXPORT_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, XLSX_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused to create $EXPORT_NAME")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                XlsxWorkbookWriter(out).use { w ->
                    for (module in ModuleType.entries) {
                        val rows = cases.filter { it.module == module }
                        if (rows.isEmpty()) continue
                        w.addSheet(
                            name = module.label,
                            header = CaseSchemaMapper.EXPORT_HEADER,
                            rows = rows.asSequence().map { CaseSchemaMapper.toExportRow(it) },
                            columnWidths = CaseSchemaMapper.EXPORT_WIDTHS,
                            numericColumns = CaseSchemaMapper.EXPORT_NUMERIC,
                        )
                    }
                }
            } ?: throw IOException("Cannot write $EXPORT_NAME")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        val sheets = cases.map { it.module }.distinct().size
        ExportResult(uri, "Documents/$EXPORT_NAME", cases.size, sheets)
    }
}
