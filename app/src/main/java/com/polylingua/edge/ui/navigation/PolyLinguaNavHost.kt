package com.polylingua.edge.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.collectLatest
import com.polylingua.edge.core.UserNotices
import com.polylingua.edge.ui.PendingRoute
import com.polylingua.edge.ui.TelemetryViewModel
import com.polylingua.edge.ui.components.PillBadge
import com.polylingua.edge.ui.components.PolyIcons
import com.polylingua.edge.ui.screens.BentoDiagnosticDashboardScreen
import com.polylingua.edge.ui.screens.CakeShadowingStudioScreen
import com.polylingua.edge.ui.screens.ElsaPhonemeLabScreen
import com.polylingua.edge.ui.screens.HelloTalkTandemHybridScreen
import com.polylingua.edge.ui.screens.HomeAndSetupScreen
import com.polylingua.edge.ui.screens.ModelSetupScreen
import com.polylingua.edge.ui.screens.QuizAndFlashcardArenaScreen
import com.polylingua.edge.ui.screens.StimulerIeltsArenaScreen
import com.polylingua.edge.ui.theme.PolyColors
import com.polylingua.edge.ui.theme.PolyFonts
import com.polylingua.edge.ui.theme.PolyType

object Routes {
    const val HOME = "home"
    const val SETUP = "setup"
    const val ARENA = "arena/{mode}?caseId={caseId}"
    const val ELSA = "elsa?caseId={caseId}"
    const val TANDEM = "tandem"
    const val CAKE = "cake"
    const val QUIZ = "quiz/{mode}?packages={packages}"
    const val DASHBOARD = "dashboard"

    fun arena(mode: String, caseId: Long = -1L) = "arena/$mode?caseId=$caseId"
    fun elsa(caseId: Long = -1L) = "elsa?caseId=$caseId"
    fun quiz(mode: String, packages: List<Long> = emptyList()) = "quiz/$mode?packages=${packages.joinToString(",")}"
}

/** Root layout: global telemetry bar above an animated navigation host. */
@Composable
fun PolyLinguaRoot(nav: NavHostController = rememberNavController()) {
    val pendingRoute by PendingRoute.route.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        val r = pendingRoute ?: return@LaunchedEffect
        PendingRoute.route.value = null
        if (PendingRoute.isValid(r)) runCatching { nav.navigate(r) }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        // collectLatest: a newer notice replaces the one on screen instead of queueing behind it.
        UserNotices.flow.collectLatest { n ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(
                n.text,
                withDismissAction = true,
                duration = if (n.kind == UserNotices.Kind.ERROR || n.text.length > 90) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }
    Box(Modifier.fillMaxSize().background(PolyColors.AmazeVoidBlack)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TelemetryTopBar()
            Box(Modifier.weight(1f)) {
                PolyLinguaNavHost(nav)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) { data ->
            Snackbar(
                data,
                containerColor = PolyColors.ObsidianSlateHigh,
                contentColor = PolyColors.PureIceWhite,
                actionColor = PolyColors.ElectricTeal,
                dismissActionContentColor = PolyColors.MutedSlateText,
            )
        }
    }
}

@Composable
fun PolyLinguaNavHost(nav: NavHostController) {
    val enter = slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(260))
    val exit = slideOutHorizontally(tween(220)) { -it / 8 } + fadeOut(tween(200))
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { enter },
        exitTransition = { exit },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(200)) },
    ) {
        composable(Routes.HOME) { HomeAndSetupScreen(nav) }
        composable(Routes.SETUP) { ModelSetupScreen(onBack = { nav.popBackStack() }) }
        composable(
            Routes.ARENA,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType },
                navArgument("caseId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { StimulerIeltsArenaScreen(nav) }
        composable(Routes.ELSA, arguments = listOf(navArgument("caseId") { type = NavType.LongType; defaultValue = -1L })) {
            ElsaPhonemeLabScreen(nav)
        }
        composable(Routes.TANDEM) { HelloTalkTandemHybridScreen(nav) }
        composable(Routes.CAKE) { CakeShadowingStudioScreen(nav) }
        composable(
            Routes.QUIZ,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType },
                navArgument("packages") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { QuizAndFlashcardArenaScreen(nav) }
        composable(Routes.DASHBOARD) { BentoDiagnosticDashboardScreen(nav) }
    }
}

/** Branding · HH:mm:ss · CPU/RAM · network tier · audio route. */
@Composable
fun TelemetryTopBar(vm: TelemetryViewModel = hiltViewModel()) {
    val clock by vm.clock.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val net by vm.network.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val voice by vm.synthesizing.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().background(PolyColors.AmazeVoidBlack).padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PolyIcons.Waveform, null, tint = PolyColors.ElectricTeal, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("PolyLingua", style = PolyType.CardTitle.copy(fontFamily = PolyFonts.Jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp))
            Text(" Edge", style = PolyType.CardTitle.copy(fontFamily = PolyFonts.Jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = PolyColors.ElectricTeal))
            Spacer(Modifier.weight(1f))
            Text(clock, style = PolyType.Numeric.copy(fontSize = 13.sp, color = PolyColors.PureIceWhite))
        }
        Spacer(Modifier.size(4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val cpuColor = when {
                snap.appCpuPercent >= 70 -> PolyColors.CrimsonAlert
                snap.appCpuPercent >= 40 -> PolyColors.AmberWarning
                else -> PolyColors.ElectricTeal
            }
            PillBadge("CPU ${snap.appCpuPercent}% · RAM ${snap.appRssMb} MB", cpuColor)
            PillBadge(net.pill, if (net.online) PolyColors.NeonMintSuccess else PolyColors.AmberWarning, filled = true)
            PillBadge(route.route.badge, if (route.route.isHeadset) PolyColors.NeonMintSuccess else PolyColors.ElectricTeal)
            if (voice != null) PillBadge("🔊 PREPARING VOICE", PolyColors.AmberWarning)
            if (snap.lowMemory) PillBadge("LOW MEMORY", PolyColors.CrimsonAlert, filled = true)
        }
    }
}
