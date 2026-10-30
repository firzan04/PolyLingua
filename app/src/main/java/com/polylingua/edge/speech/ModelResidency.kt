package com.polylingua.edge.speech

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/** An engine holding a native model in memory that can be released under memory pressure. */
interface ResidentModel {
    val residencyKey: String
    val residentMb: Int

    /** Frees native memory. Must be thread-safe and leave the engine able to reload later. */
    fun releaseForMemory()
}

/**
 * Keeps the sum of loaded native models under the POCO M4 Pro budget (1.8 GB of the 6/8 GB LPDDR4X,
 * leaving room for MIUI, the Compose UI and audio buffers). Loading a model first reserves its
 * size; least-recently-used models are released until the reservation fits.
 */
@Singleton
class ModelResidency @Inject constructor() {
    companion object {
        const val BUDGET_MB = 1800
        private const val TAG = "PolyLinguaResidency"
    }

    private val loaded = LinkedHashMap<String, ResidentModel>(8, 0.75f, true)

    @Synchronized
    fun loadedMb(): Int = loaded.values.sumOf { it.residentMb }

    @Synchronized
    fun loadedKeys(): List<String> = loaded.keys.toList()

    /** Makes room for [mb] for [key] (evicting LRU models other than [key] and [pinned]). */
    fun reserve(key: String, mb: Int, pinned: Set<String> = emptySet()) {
        val victims = ArrayList<ResidentModel>()
        synchronized(this) {
            var used = loaded.filterKeys { it != key }.values.sumOf { it.residentMb }
            val iterator = loaded.entries.iterator()
            while (used + mb > BUDGET_MB && iterator.hasNext()) {
                val (k, model) = iterator.next()
                if (k == key || k in pinned) continue
                victims += model
                used -= model.residentMb
                iterator.remove()
            }
        }
        victims.forEach {
            Log.i(TAG, "Releasing ${it.residencyKey} (${it.residentMb} MB) to fit $key ($mb MB)")
            runCatching { it.releaseForMemory() }
        }
    }

    @Synchronized
    fun register(model: ResidentModel) {
        loaded[model.residencyKey] = model
    }

    @Synchronized
    fun touch(key: String) {
        loaded[key]
    }

    @Synchronized
    fun unregister(key: String) {
        loaded.remove(key)
    }
}
