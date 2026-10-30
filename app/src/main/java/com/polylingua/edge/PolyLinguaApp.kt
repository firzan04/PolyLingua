package com.polylingua.edge

import android.app.Application
import android.util.Log
import com.polylingua.edge.core.LinguisticResources
import com.polylingua.edge.data.CaseRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PolyLinguaApp : Application() {

    @Inject lateinit var linguistics: LinguisticResources
    @Inject lateinit var cases: CaseRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        PolyLinguaForegroundService.ensureChannel(this)
        appScope.launch {
            runCatching { linguistics.warmUp() }.onFailure { Log.e("PolyLingua", "Asset warm-up failed", it) }
            runCatching { cases.ensureBuiltInSeeded() }.onFailure { Log.e("PolyLingua", "Curriculum seeding failed", it) }
        }
    }
}
