package com.polylingua.edge.data.xlsx

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Minimal streaming XML pull parser for SpreadsheetML (no DTDs, standard + numeric entities,
 * CDATA, comments, processing instructions, namespace prefixes stripped to local names).
 * Dependency-free so the same code runs on Android and in JVM unit tests, and memory stays flat
 * even for 10,000+ row worksheets.
 */
class MiniXmlPullParser(source: Reader) {
    enum class Event { START_TAG, END_TAG, TEXT, END_DOCUMENT }

    private val reader = if (source is java.io.BufferedReader) source else source.buffered(64 * 1024)
    private var peeked: Int = -2
    private var pendingEnd: String? = null

    var name: String = ""
        private set
    var text: String = ""
        private set
    val attributes: MutableMap<String, String> = HashMap()

    private fun read(): Int {
        if (peeked != -2) { val p = peeked; peeked = -2; return p }
        return reader.read()
    }

    private fun peek(): Int {
        if (peeked == -2) peeked = reader.read()
        return peeked
    }

    fun next(): Event {
        pendingEnd?.let { pendingEnd = null; name = it; return Event.END_TAG }
        while (true) {
            val c = read()
            if (c == -1) return Event.END_DOCUMENT
            if (c != '<'.code) {
                val sb = StringBuilder()
                sb.append(c.toChar())
                while (peek() != -1 && peek() != '<'.code) sb.append(read().toChar())
                text = decodeEntities(sb)
                return Event.TEXT
            }
            when (peek()) {
                '?'.code -> { skipUntil("?>"); continue }
                '!'.code -> {
                    read()
                    if (peek() == '-'.code) { skipUntil("-->"); continue }
                    if (peek() == '['.code) {
                        // <![CDATA[ ... ]]>
                        repeat(7) { read() }
                        val sb = StringBuilder()
                        while (true) {
                            val ch = read()
                            if (ch == -1) break
                            sb.append(ch.toChar())
                            if (sb.length >= 3 && sb.endsWith("]]>")) { sb.setLength(sb.length - 3); break }
                        }
                        text = sb.toString()
                        return Event.TEXT
                    }
                    skipUntil(">"); continue
                }
                '/'.code -> {
                    read()
                    val sb = StringBuilder()
                    while (true) {
                        val ch = read()
                        if (ch == -1 || ch == '>'.code) break
                        if (!ch.toChar().isWhitespace()) sb.append(ch.toChar())
                    }
                    name = localName(sb.toString())
                    return Event.END_TAG
                }
                else -> {
                    attributes.clear()
                    val nameSb = StringBuilder()
                    while (true) {
                        val ch = peek()
                        if (ch == -1 || ch == '>'.code || ch == '/'.code || ch.toChar().isWhitespace()) break
                        nameSb.append(read().toChar())
                    }
                    name = localName(nameSb.toString())
                    var selfClosing = false
                    while (true) {
                        var ch = read()
                        while (ch != -1 && ch.toChar().isWhitespace()) ch = read()
                        if (ch == -1 || ch == '>'.code) break
                        if (ch == '/'.code) { selfClosing = true; continue }
                        val key = StringBuilder()
                        key.append(ch.toChar())
                        while (peek() != -1 && peek() != '='.code && !peek().toChar().isWhitespace() && peek() != '>'.code && peek() != '/'.code) key.append(read().toChar())
                        while (peek() != -1 && peek().toChar().isWhitespace()) read()
                        if (peek() != '='.code) { attributes[localName(key.toString())] = ""; continue }
                        read() // '='
                        while (peek() != -1 && peek().toChar().isWhitespace()) read()
                        val quote = read()
                        val value = StringBuilder()
                        if (quote == '"'.code || quote == '\''.code) {
                            while (true) {
                                val v = read()
                                if (v == -1 || v == quote) break
                                value.append(v.toChar())
                            }
                        } else if (quote != -1) {
                            value.append(quote.toChar())
                            while (peek() != -1 && !peek().toChar().isWhitespace() && peek() != '>'.code) value.append(read().toChar())
                        }
                        attributes[localName(key.toString())] = decodeEntities(value)
                    }
                    if (selfClosing) pendingEnd = name
                    return Event.START_TAG
                }
            }
        }
    }

    private fun skipUntil(terminator: String) {
        val window = StringBuilder()
        while (true) {
            val c = read()
            if (c == -1) return
            window.append(c.toChar())
            if (window.length > terminator.length) window.deleteCharAt(0)
            if (window.toString() == terminator) return
        }
    }

    companion object {
        fun localName(qName: String): String = qName.substringAfter(':')

        fun decodeEntities(sb: CharSequence): String {
            if (sb.indexOf('&') < 0) return sb.toString()
            val out = StringBuilder(sb.length)
            var i = 0
            while (i < sb.length) {
                val c = sb[i]
                if (c == '&') {
                    val semi = sb.indexOf(';', i)
                    if (semi > i && semi - i <= 10) {
                        val ent = sb.substring(i + 1, semi)
                        val decoded: String? = when {
                            ent == "lt" -> "<"
                            ent == "gt" -> ">"
                            ent == "amp" -> "&"
                            ent == "quot" -> "\""
                            ent == "apos" -> "'"
                            ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                            ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                            else -> null
                        }
                        if (decoded != null) { out.append(decoded); i = semi + 1; continue }
                    }
                }
                out.append(c)
                i++
            }
            return out.toString()
        }

        private fun CharSequence.indexOf(ch: Char, from: Int = 0): Int {
            for (k in from until length) if (this[k] == ch) return k
            return -1
        }
    }
}

/**
 * Streaming .xlsx reader: workbook sheet list, shared-string table, then one worksheet at a time
 * row by row. Handles shared/inline/formula strings, booleans, numbers (integers rendered without
 * ".0"), sparse cells and absolute/relative relationship targets. Supports 1,000+ sheets.
 */
class XlsxWorkbookReader private constructor(private val zip: ZipFile) : Closeable {

    data class SheetInfo(val index: Int, val name: String, val path: String)

    val sheets: List<SheetInfo>
    private val sharedStrings: List<String> by lazy { readSharedStrings() }

    init {
        val rels = HashMap<String, String>()
        zip.getEntry("xl/_rels/workbook.xml.rels")?.let { entry ->
            parse(zip.getInputStream(entry)) { p ->
                while (true) {
                    val ev = p.next()
                    if (ev == MiniXmlPullParser.Event.END_DOCUMENT) break
                    if (ev == MiniXmlPullParser.Event.START_TAG && p.name == "Relationship") {
                        val id = p.attributes["Id"] ?: continue
                        val target = p.attributes["Target"] ?: continue
                        rels[id] = normalizeTarget(target)
                    }
                }
            }
        }
        val list = ArrayList<SheetInfo>()
        val wb = zip.getEntry("xl/workbook.xml") ?: throw IllegalArgumentException("Not an .xlsx workbook (xl/workbook.xml missing)")
        parse(zip.getInputStream(wb)) { p ->
            var idx = 0
            while (true) {
                val ev = p.next()
                if (ev == MiniXmlPullParser.Event.END_DOCUMENT) break
                if (ev == MiniXmlPullParser.Event.START_TAG && p.name == "sheet") {
                    val name = p.attributes["name"] ?: "Sheet${idx + 1}"
                    val rid = p.attributes["id"]
                    val path = rid?.let { rels[it] } ?: "xl/worksheets/sheet${idx + 1}.xml"
                    if (zip.getEntry(path) != null) list += SheetInfo(idx, name, path)
                    idx++
                }
            }
        }
        sheets = list
    }

    private fun normalizeTarget(target: String): String {
        val t = target.replace('\\', '/')
        return when {
            t.startsWith("/") -> t.removePrefix("/")
            t.startsWith("xl/") -> t
            else -> "xl/" + t.removePrefix("./")
        }
    }

    private fun readSharedStrings(): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        parse(zip.getInputStream(entry)) { p ->
            var inSi = false
            var inT = false
            var inPhonetic = false
            val sb = StringBuilder()
            while (true) {
                when (p.next()) {
                    MiniXmlPullParser.Event.END_DOCUMENT -> break
                    MiniXmlPullParser.Event.START_TAG -> when (p.name) {
                        "si" -> { inSi = true; sb.setLength(0) }
                        "t" -> inT = true
                        "rPh" -> inPhonetic = true
                    }
                    MiniXmlPullParser.Event.END_TAG -> when (p.name) {
                        "si" -> { inSi = false; out += sb.toString() }
                        "t" -> inT = false
                        "rPh" -> inPhonetic = false
                    }
                    MiniXmlPullParser.Event.TEXT -> if (inSi && inT && !inPhonetic) sb.append(p.text)
                }
            }
        }
        return out
    }

    /** Streams rows of [sheet]. [onRow] receives the 0-based row index and dense cell strings. */
    fun readSheet(sheet: SheetInfo, onRow: (rowIndex: Int, cells: List<String>) -> Unit) {
        val entry = zip.getEntry(sheet.path) ?: return
        val strings = sharedStrings
        parse(zip.getInputStream(entry)) { p ->
            var rowIndex = -1
            var cells = ArrayList<String>()
            var cellCol = 0
            var cellType = ""
            var inV = false
            var inIsT = false
            var inIs = false
            val value = StringBuilder()
            var nextCol = 0
            while (true) {
                when (p.next()) {
                    MiniXmlPullParser.Event.END_DOCUMENT -> break
                    MiniXmlPullParser.Event.START_TAG -> when (p.name) {
                        "row" -> {
                            rowIndex = (p.attributes["r"]?.toIntOrNull()?.minus(1)) ?: (rowIndex + 1)
                            cells = ArrayList()
                            nextCol = 0
                        }
                        "c" -> {
                            cellCol = p.attributes["r"]?.let { columnIndex(it) } ?: nextCol
                            cellType = p.attributes["t"] ?: "n"
                            value.setLength(0)
                        }
                        "v" -> inV = true
                        "is" -> inIs = true
                        "t" -> if (inIs) inIsT = true
                    }
                    MiniXmlPullParser.Event.END_TAG -> when (p.name) {
                        "v" -> inV = false
                        "t" -> inIsT = false
                        "is" -> inIs = false
                        "c" -> {
                            val raw = value.toString()
                            val resolved = when (cellType) {
                                "s" -> raw.trim().toIntOrNull()?.let { strings.getOrNull(it) } ?: ""
                                "inlineStr", "str" -> raw
                                "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                                "e" -> ""
                                else -> formatNumber(raw)
                            }
                            while (cells.size < cellCol) cells += ""
                            if (cells.size == cellCol) cells += resolved else cells[cellCol] = resolved
                            nextCol = cellCol + 1
                        }
                        "row" -> if (rowIndex >= 0) onRow(rowIndex, cells)
                    }
                    MiniXmlPullParser.Event.TEXT -> if (inV || inIsT) value.append(p.text)
                }
            }
        }
    }

    override fun close() = zip.close()

    companion object {
        fun open(file: File): XlsxWorkbookReader = XlsxWorkbookReader(ZipFile(file))

        private inline fun parse(stream: InputStream, block: (MiniXmlPullParser) -> Unit) {
            stream.use { s -> block(MiniXmlPullParser(InputStreamReader(s, Charsets.UTF_8))) }
        }

        /** "AB12" -> 27 (0-based column index). */
        fun columnIndex(ref: String): Int {
            var col = 0
            for (ch in ref) {
                if (ch in 'A'..'Z') col = col * 26 + (ch - 'A' + 1)
                else if (ch in 'a'..'z') col = col * 26 + (ch - 'a' + 1)
                else break
            }
            return (col - 1).coerceAtLeast(0)
        }

        fun columnName(index: Int): String {
            var n = index + 1
            val sb = StringBuilder()
            while (n > 0) {
                val rem = (n - 1) % 26
                sb.insert(0, ('A' + rem))
                n = (n - 1) / 26
            }
            return sb.toString()
        }

        /** Excel numbers: integers without ".0", floats trimmed of binary noise (4.0000000001 -> 4). */
        fun formatNumber(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return ""
            val d = t.toDoubleOrNull() ?: return t
            if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
            val s = String.format(Locale.US, "%.10f", d).trimEnd('0').trimEnd('.')
            return s
        }
    }
}

/**
 * Streaming .xlsx writer (SpreadsheetML, inline strings, bold frozen header row, column widths).
 * Each sheet is written straight into the zip as rows are produced, so exporting 1,000+ cases
 * never materialises the whole workbook in memory.
 */
class XlsxWorkbookWriter(output: OutputStream) : Closeable {
    private val zip = ZipOutputStream(output.buffered(64 * 1024))
    private val sheetNames = ArrayList<String>()
    private var closed = false

    fun addSheet(
        name: String,
        header: List<String>,
        rows: Sequence<List<String>>,
        columnWidths: List<Int> = emptyList(),
        numericColumns: Set<Int> = emptySet(),
    ) {
        val safe = uniqueSheetName(name)
        sheetNames += safe
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet${sheetNames.size}.xml"))
        val w = BufferedWriter(OutputStreamWriter(NonClosing(zip), Charsets.UTF_8), 64 * 1024)
        w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        w.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        w.write("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        w.write("""<sheetFormatPr defaultRowHeight="15"/>""")
        if (columnWidths.isNotEmpty()) {
            w.write("<cols>")
            columnWidths.forEachIndexed { i, width ->
                w.write("""<col min="${i + 1}" max="${i + 1}" width="$width" customWidth="1"/>""")
            }
            w.write("</cols>")
        }
        w.write("<sheetData>")
        writeRow(w, 1, header, emptySet(), style = 1)
        var r = 2
        for (row in rows) {
            writeRow(w, r, row, numericColumns, style = 0)
            r++
        }
        w.write("</sheetData></worksheet>")
        w.flush()
        zip.closeEntry()
    }

    private fun writeRow(w: BufferedWriter, r: Int, cells: List<String>, numeric: Set<Int>, style: Int) {
        w.write("""<row r="$r">""")
        for ((c, value) in cells.withIndex()) {
            if (value.isEmpty()) continue
            val ref = XlsxWorkbookReader.columnName(c) + r
            val styleAttr = if (style != 0) """ s="$style"""" else ""
            if (c in numeric && NUMERIC.matches(value)) {
                w.write("""<c r="$ref"$styleAttr><v>$value</v></c>""")
            } else {
                w.write("""<c r="$ref" t="inlineStr"$styleAttr><is><t xml:space="preserve">""")
                w.write(escape(value))
                w.write("</t></is></c>")
            }
        }
        w.write("</row>")
    }

    override fun close() {
        if (closed) return
        closed = true
        if (sheetNames.isEmpty()) addSheet("Sheet1", listOf(""), emptySequence())
        entry("[Content_Types].xml", buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            append("""<Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            for (i in 1..sheetNames.size) {
                append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
            }
            append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
            append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
            append("""<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
            append("</Types>")
        })
        entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>""")
        entry("xl/workbook.xml", buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
            sheetNames.forEachIndexed { i, n -> append("""<sheet name="${escape(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
            append("</sheets></workbook>")
        })
        entry("xl/_rels/workbook.xml.rels", buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            for (i in 1..sheetNames.size) {
                append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
            }
            append("""<Relationship Id="rId${sheetNames.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
            append("</Relationships>")
        })
        entry("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0B0F19"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        entry("docProps/core.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>PolyLingua Master Cases</dc:title><dc:creator>PolyLingua Edge</dc:creator><dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified></cp:coreProperties>""")
        entry("docProps/app.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>PolyLingua Edge</Application></Properties>""")
        zip.finish()
        zip.close()
    }

    private fun entry(path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun uniqueSheetName(raw: String): String {
        var base = raw.replace(Regex("[\\[\\]:*?/\\\\]"), "_").trim().ifEmpty { "Sheet" }
        if (base.length > 31) base = base.substring(0, 31)
        var candidate = base
        var n = 2
        while (sheetNames.any { it.equals(candidate, ignoreCase = true) }) {
            val suffix = "_$n"
            candidate = base.take(31 - suffix.length) + suffix
            n++
        }
        return candidate
    }

    private class NonClosing(private val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun flush() = delegate.flush()
        override fun close() = delegate.flush()
    }

    companion object {
        private val NUMERIC = Regex("-?\\d+(\\.\\d+)?")

        fun escape(s: String): String {
            val sb = StringBuilder(s.length + 16)
            for (ch in s) {
                when {
                    ch == '&' -> sb.append("&amp;")
                    ch == '<' -> sb.append("&lt;")
                    ch == '>' -> sb.append("&gt;")
                    ch == '"' -> sb.append("&quot;")
                    ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
                    ch.code < 0x20 || ch == '￾' || ch == '￿' -> Unit  // invalid in XML 1.0
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
