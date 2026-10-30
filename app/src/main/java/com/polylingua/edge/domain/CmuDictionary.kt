package com.polylingua.edge.domain

import java.io.InputStream

/**
 * CMU Pronouncing Dictionary (126k words, 39 ARPAbet phonemes + stress digits) stored as a
 * byte-sorted "word<TAB>PRON1|PRON2" table (assets/phonetics/cmudict_sorted.tsv). Lookups are
 * binary searches over the raw bytes, so the whole dictionary costs ~4 MB instead of ~40 MB of
 * Java strings. Out-of-vocabulary words fall back to a rule-based letter-to-sound guess.
 */
class CmuDictionary private constructor(private val data: ByteArray) {

    private val lineStarts: IntArray

    init {
        var count = 0
        for (b in data) if (b == '\n'.code.toByte()) count++
        val starts = IntArray(count + 1)
        var n = 0
        starts[n++] = 0
        for (i in data.indices) {
            if (data[i] == '\n'.code.toByte() && i + 1 < data.size) starts[n++] = i + 1
        }
        lineStarts = starts.copyOf(n)
    }

    val wordCount: Int get() = lineStarts.size

    private fun compareKey(lineStart: Int, key: ByteArray): Int {
        var i = 0
        var p = lineStart
        while (true) {
            val lineByte = if (p < data.size && data[p] != '\t'.code.toByte() && data[p] != '\n'.code.toByte()) data[p].toInt() and 0xFF else -1
            val keyByte = if (i < key.size) key[i].toInt() and 0xFF else -1
            if (lineByte == -1 && keyByte == -1) return 0
            if (lineByte != keyByte) return lineByte.compareTo(keyByte)
            i++
            p++
        }
    }

    /** All CMUdict pronunciations for [word] (ARPAbet with stress), or null if unknown. */
    fun lookup(word: String): List<List<String>>? {
        val key = TextAnalysis.normalize(word).trim().trim('.', ',', '!', '?', '"', ';', ':').toByteArray(Charsets.UTF_8)
        if (key.isEmpty()) return null
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareKey(lineStarts[mid], key)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> {
                    val start = lineStarts[mid]
                    var tab = start
                    while (tab < data.size && data[tab] != '\t'.code.toByte()) tab++
                    var end = tab
                    while (end < data.size && data[end] != '\n'.code.toByte()) end++
                    val prons = String(data, tab + 1, end - tab - 1, Charsets.UTF_8)
                    return prons.split('|').map { p -> p.trim().split(' ').filter { it.isNotEmpty() } }
                }
            }
        }
        return null
    }

    /** First CMUdict pronunciation, else lemma-based lookup, else letter-to-sound guess. */
    fun pronounce(word: String): List<String> {
        lookup(word)?.firstOrNull()?.let { return it }
        val w = TextAnalysis.normalize(word)
        if (w.endsWith("'s")) lookup(w.dropLast(2))?.firstOrNull()?.let { return it + "Z" }
        return LetterToSound.guess(w)
    }

    fun isKnown(word: String): Boolean = lookup(word) != null

    companion object {
        fun load(stream: InputStream): CmuDictionary = CmuDictionary(stream.use { it.readBytes() })
    }
}

/** Compact English letter-to-sound rules for OOV words (names, coinages). Approximate by design. */
object LetterToSound {
    private val RULES: List<Pair<String, List<String>>> = listOf(
        "tion" to listOf("SH", "AH0", "N"), "sion" to listOf("ZH", "AH0", "N"), "ough" to listOf("AO1"),
        "augh" to listOf("AO1"), "igh" to listOf("AY1"), "tch" to listOf("CH"), "dge" to listOf("JH"),
        "sch" to listOf("S", "K"), "ph" to listOf("F"), "th" to listOf("TH"), "sh" to listOf("SH"), "ch" to listOf("CH"),
        "ck" to listOf("K"), "ng" to listOf("NG"), "qu" to listOf("K", "W"), "wh" to listOf("W"), "wr" to listOf("R"),
        "kn" to listOf("N"), "gh" to listOf(), "ee" to listOf("IY1"), "ea" to listOf("IY1"), "oo" to listOf("UW1"),
        "ou" to listOf("AW1"), "ow" to listOf("OW1"), "oi" to listOf("OY1"), "oy" to listOf("OY1"), "ai" to listOf("EY1"),
        "ay" to listOf("EY1"), "au" to listOf("AO1"), "aw" to listOf("AO1"), "ie" to listOf("IY1"), "ei" to listOf("EY1"),
        "ue" to listOf("UW1"), "ar" to listOf("AA1", "R"), "er" to listOf("ER0"), "ir" to listOf("ER1"), "ur" to listOf("ER1"),
        "or" to listOf("AO1", "R"), "a" to listOf("AE1"), "e" to listOf("EH1"), "i" to listOf("IH1"), "o" to listOf("AA1"),
        "u" to listOf("AH1"), "y" to listOf("IY0"), "b" to listOf("B"), "c" to listOf("K"), "d" to listOf("D"), "f" to listOf("F"),
        "g" to listOf("G"), "h" to listOf("HH"), "j" to listOf("JH"), "k" to listOf("K"), "l" to listOf("L"), "m" to listOf("M"),
        "n" to listOf("N"), "p" to listOf("P"), "q" to listOf("K"), "r" to listOf("R"), "s" to listOf("S"), "t" to listOf("T"),
        "v" to listOf("V"), "w" to listOf("W"), "x" to listOf("K", "S"), "z" to listOf("Z"),
    )

    fun guess(word: String): List<String> {
        val w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        // silent final e (make, time)
        val body = if (w.length > 3 && w.endsWith("e") && w[w.length - 2] !in "aeiouy") w.dropLast(1) else w
        while (i < body.length) {
            var matched = false
            for ((pattern, phones) in RULES) {
                if (body.startsWith(pattern, i)) {
                    out += phones
                    i += pattern.length
                    matched = true
                    break
                }
            }
            if (!matched) i++
        }
        // Keep only the first vowel primary-stressed; the rest unstressed.
        var stressed = false
        return out.map { p ->
            if (PhoneticArticulationDatabase.isVowel(p)) {
                val base = PhoneticArticulationDatabase.baseSymbol(p)
                if (!stressed) { stressed = true; base + "1" } else if (base == "AH" || base == "ER") base + "0" else base + "0"
            } else p
        }
    }
}
