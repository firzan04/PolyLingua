package com.polylingua.edge.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polylingua.edge.core.DeviceTelemetry
import com.polylingua.edge.core.TelemetrySnapshot
import com.polylingua.edge.domain.model.SpeakingAssessment
import com.polylingua.edge.domain.model.SpeechSample
import com.polylingua.edge.domain.model.TaskContext
import com.polylingua.edge.dsp.AudioRouteMonitor
import com.polylingua.edge.dsp.RouteInfo
import com.polylingua.edge.network.ConnectivityAndWebRtcManager
import com.polylingua.edge.network.NetworkStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the Bento dashboard renders for one assessed response or mock test. */
data class DashboardPayload(
    val context: TaskContext,
    val assessment: SpeakingAssessment,
    val sample: SpeechSample,
    val engine: String,
    val stem: String?,
    val sessionId: Long?,
    val audio: FloatArray?,
    val caseId: Long?,
)

/** Hands assessment results from the arena / history vault to the dashboard. */
@Singleton
class DiagnosticSessionStore @Inject constructor() {
    val current = MutableStateFlow<DashboardPayload?>(null)
}

/** Workbook opened via "Open with PolyLingua" before the home screen was composed. */
object PendingImport {
    val uri = MutableStateFlow<Uri?>(null)
}

/** Navigation requested by a launcher shortcut (intent extra "route"). */
object PendingRoute {
    val route = MutableStateFlow<String?>(null)
    private val allowed = listOf("home", "setup", "arena/", "elsa", "tandem", "cake", "quiz/", "dashboard")
    fun isValid(r: String): Boolean = allowed.any { r == it || r.startsWith(it) }
}

@HiltViewModel
class TelemetryViewModel @Inject constructor(
    private val telemetry: DeviceTelemetry,
    network: ConnectivityAndWebRtcManager,
    routes: AudioRouteMonitor,
    tts: com.polylingua.edge.speech.SpeechSynthesisEngine,
) : ViewModel() {
    val snapshot: StateFlow<TelemetrySnapshot> = telemetry.snapshot
    /** Non-null while a voice is being synthesised (first use of a neural voice takes a moment). */
    val synthesizing: StateFlow<String?> = tts.synthesizing
    val network: StateFlow<NetworkStatus> = network.network
    val route: StateFlow<RouteInfo> = routes.route

    private val _clock = MutableStateFlow(now())
    val clock: StateFlow<String> = _clock

    init {
        telemetry.acquire()
        viewModelScope.launch {
            while (isActive) {
                _clock.value = now()
                delay(1000L - System.currentTimeMillis() % 1000L)
            }
        }
    }

    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    override fun onCleared() {
        telemetry.release()
    }
}
