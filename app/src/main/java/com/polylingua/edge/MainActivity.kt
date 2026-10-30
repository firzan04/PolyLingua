package com.polylingua.edge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.polylingua.edge.ui.PendingImport
import com.polylingua.edge.ui.navigation.PolyLinguaRoot
import com.polylingua.edge.ui.theme.PolyLinguaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        requestHighestRefreshRate()
        handleIntent(intent)
        requestStartupPermissions()
        setContent {
            PolyLinguaTheme {
                PolyLinguaRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Open with PolyLingua" for .xlsx workbooks shared from a file manager or Drive. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { PendingImport.uri.value = it }
    }

    /**
     * 90 Hz AMOLED: pick the display mode with the highest refresh rate at the current resolution
     * (MIUI honours preferredDisplayModeId unless the user locked 60 Hz in settings).
     */
    private fun requestHighestRefreshRate() {
        val d = display ?: return
        val current = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        val attrs: WindowManager.LayoutParams = window.attributes
        attrs.preferredDisplayModeId = best.modeId
        attrs.preferredRefreshRate = best.refreshRate
        window.attributes = attrs
    }

    private fun requestStartupPermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }
}
