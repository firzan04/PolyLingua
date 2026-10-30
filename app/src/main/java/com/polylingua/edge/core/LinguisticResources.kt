package com.polylingua.edge.core

import android.content.Context
import com.polylingua.edge.domain.CefrLexicon
import com.polylingua.edge.domain.CmuDictionary
import com.polylingua.edge.domain.PedagogicalRubricEngine
import com.polylingua.edge.domain.PhoneticArticulationDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundled offline linguistic assets, loaded lazily on first use:
 *  - CMUdict 0.7b (126k words, sorted TSV, binary-searched in place: ~3.5 MB heap)
 *  - CEFR-J 1.5 + Octanove C1/C2 word profiles (~8.8k headwords)
 *  - charsiu phone-classifier label map (42 CMU classes) for GOP
 */
@Singleton
class LinguisticResources @Inject constructor(@ApplicationContext private val context: Context) {

    val cmu: CmuDictionary by lazy {
        context.assets.open("phonetics/cmudict_sorted.tsv").use { CmuDictionary.load(it) }
    }

    val cefr: CefrLexicon by lazy {
        context.assets.open("lexicon/cefrj-vocabulary-profile-1.5.csv").use { a ->
            context.assets.open("lexicon/octanove-vocabulary-profile-c1c2-1.0.csv").use { b ->
                CefrLexicon.load(a, b)
            }
        }
    }

    val rubric: PedagogicalRubricEngine by lazy { PedagogicalRubricEngine(cefr) }

    /** Phone-classifier labels by output index (from the bundled vocab JSON). */
    val gopLabels: List<String> by lazy {
        val json = context.assets.open("phonetics/charsiu_en_cmu_vocab.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val o = JSONObject(json)
        val pairs = o.keys().asSequence().map { it to o.getInt(it) }.sortedBy { it.second }.toList()
        val out = MutableList(pairs.maxOf { it.second } + 1) { "[UNK]" }
        pairs.forEach { (label, idx) -> out[idx] = label }
        out
    }

    /** IPA (RP by default) for a single word, e.g. "thought" -> /ˈθɔːt/. */
    fun ipaOf(word: String, rp: Boolean = true): String {
        val clean = word.trim().trim('.', ',', '!', '?', ';', ':', '"', '(', ')').lowercase()
        if (clean.isEmpty()) return ""
        return PhoneticArticulationDatabase.toIpa(cmu.pronounce(clean), rp = rp, stressMarks = true)
    }

    /** IPA transcription of a sentence, word by word. */
    fun ipaOfSentence(text: String, rp: Boolean = true): String =
        text.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { ipaOf(it, rp) }.let { if (it.isBlank()) "" else "/$it/" }

    /** Loads every lazy asset on the DSP cluster so the first assessment is not delayed. */
    suspend fun warmUp() = withContext(CoreExecutors.dsp) {
        cmu.wordCount
        cefr.size
        rubric.hashCode()
        gopLabels.size
    }
}
