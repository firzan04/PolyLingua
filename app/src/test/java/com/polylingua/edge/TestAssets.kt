package com.polylingua.edge

import com.polylingua.edge.domain.CefrLexicon
import com.polylingua.edge.domain.CmuDictionary
import java.io.File

/** Loads the bundled assets straight from the source tree for JVM unit tests. */
object TestAssets {
    private val root: File by lazy {
        listOf("src/main/assets", "app/src/main/assets")
            .map { File(it) }
            .firstOrNull { File(it, "phonetics/cmudict_sorted.tsv").isFile }
            ?: error("Cannot locate app/src/main/assets from ${File(".").absolutePath}")
    }

    fun file(path: String): File = File(root, path)

    val cmu: CmuDictionary by lazy { file("phonetics/cmudict_sorted.tsv").inputStream().use { CmuDictionary.load(it) } }

    val cefr: CefrLexicon by lazy {
        file("lexicon/cefrj-vocabulary-profile-1.5.csv").inputStream().use { a ->
            file("lexicon/octanove-vocabulary-profile-c1c2-1.0.csv").inputStream().use { b -> CefrLexicon.load(a, b) }
        }
    }
}
