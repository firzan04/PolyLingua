package com.polylingua.edge.domain

import com.polylingua.edge.domain.model.CefrLevel
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** CEFR level lookup for single words. */
fun interface CefrLookup {
    fun levelOf(word: String): CefrLevel?
}

/**
 * Word-level CEFR profile built from two openly licensed datasets bundled in assets/lexicon:
 *  - CEFR-J Vocabulary Profile 1.5 (A1-B2, ~7,800 headwords; Tono Laboratory, TUFS - free for
 *    research and commercial use with citation)
 *  - Octanove Vocabulary Profile C1/C2 1.0 (~2,100 headwords; CC BY-SA 4.0)
 * When a headword appears with several parts of speech the lowest (easiest) level wins, which
 * keeps the B2+/C1+ ratios conservative.
 */
class CefrLexicon private constructor(private val levels: Map<String, CefrLevel>) : CefrLookup {

    val size: Int get() = levels.size

    override fun levelOf(word: String): CefrLevel? {
        for (candidate in TextAnalysis.lemmaCandidates(word)) {
            levels[candidate]?.let { return it }
        }
        return null
    }

    companion object {
        fun load(cefrJ: InputStream, octanove: InputStream): CefrLexicon {
            val map = HashMap<String, CefrLevel>(12_000)
            fun ingest(stream: InputStream) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                    var header = true
                    for (line in lines) {
                        if (header) { header = false; continue }
                        val cols = parseCsvLine(line)
                        if (cols.size < 3) continue
                        val level = CefrLevel.parse(cols[2]) ?: continue
                        for (variant in cols[0].split('/')) {
                            val key = TextAnalysis.normalize(variant.trim())
                            if (key.isEmpty()) continue
                            val existing = map[key]
                            if (existing == null || level.rank < existing.rank) map[key] = level
                        }
                    }
                }
            }
            ingest(cefrJ)
            ingest(octanove)
            return CefrLexicon(map)
        }

        /** RFC-4180-ish single-line CSV parser (quoted fields, doubled quotes). */
        fun parseCsvLine(line: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> inQuotes = !inQuotes
                    c == ',' && !inQuotes -> { out += sb.toString(); sb.setLength(0) }
                    else -> sb.append(c)
                }
                i++
            }
            out += sb.toString()
            return out
        }
    }
}
