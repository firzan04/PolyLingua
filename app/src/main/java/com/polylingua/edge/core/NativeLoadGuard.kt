package com.polylingua.edge.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Crash-loop protection for native model loading. Some inference runtimes terminate the process
 * (abort/exit) instead of returning an error when a model is incompatible with the device. Before
 * a model is loaded we persist a marker; if the process dies before [end], the next launch finds
 * the marker, blocks that model and tells the learner, instead of crashing on every attempt.
 */
@Singleton
class NativeLoadGuard @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("polylingua_native_guard", Context.MODE_PRIVATE)

    /** Models that crashed the app during loading (detected at startup). */
    val blocked: Set<String> get() = prefs.getStringSet(KEY_BLOCKED, emptySet()).orEmpty()

    init {
        val pending = prefs.getString(KEY_PENDING, null)
        if (pending != null) {
            prefs.edit()
                .putStringSet(KEY_BLOCKED, blocked + pending)
                .remove(KEY_PENDING)
                .apply()
            DiagLog.e("PolyLinguaGuard", "Model '$pending' crashed the app while loading last time; it is now disabled.")
            UserNotices.post("A model ($pending) crashed while loading on this phone and was disabled. Choose another in Models & settings.", UserNotices.Kind.ERROR)
        }
    }

    fun isBlocked(key: String): Boolean = key in blocked

    fun begin(key: String) {
        prefs.edit().putString(KEY_PENDING, key).commit()
    }

    fun end() {
        prefs.edit().remove(KEY_PENDING).apply()
    }

    fun unblock(key: String) {
        prefs.edit().putStringSet(KEY_BLOCKED, blocked - key).apply()
    }

    /** Runs [load] under the guard. */
    inline fun <T> guarded(key: String, load: () -> T): T {
        begin(key)
        try {
            return load()
        } finally {
            end()
        }
    }

    companion object {
        private const val KEY_PENDING = "pending"
        private const val KEY_BLOCKED = "blocked"
    }
}
