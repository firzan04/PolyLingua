package com.polylingua.edge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.polylingua.edge.nativebridge.CpuAffinityNative
import com.polylingua.edge.nativebridge.NativeLoader
import dagger.hilt.android.AndroidEntryPoint

// Bootstrap activity used to validate the CI toolchain; replaced by the full navigation host.
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = if (NativeLoader.ensureLoaded()) CpuAffinityNative.nativeDescribeTopology() else NativeLoader.loadError
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text(text = info ?: "", color = Color.White)
            }
        }
    }
}
