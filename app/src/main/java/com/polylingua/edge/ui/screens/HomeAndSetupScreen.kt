package com.polylingua.edge.ui.screens

import android.content.ActivityNotFoundException
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.polylingua.edge.core.AccentPreference
import com.polylingua.edge.core.CoreExecutors
import com.polylingua.edge.core.ModelStorage
import com.polylingua.edge.data.ExcelDynamicIngestor
import com.polylingua.edge.data.ImportProgress
import com.polylingua.edge.data.SessionResultEntity
import com.polylingua.edge.domain.model.CefrLevel
import com.polylingua.edge.speech.AsrBackend
import com.polylingua.edge.speech.LlmState
import com.polylingua.edge.speech.ModelCatalog
import com.polylingua.edge.speech.ModelKind
import com.polylingua.edge.speech.PackStatus
import com.polylingua.edge.ui.PendingImport
import com.polylingua.edge.ui.components.Banner
import com.polylingua.edge.ui.components.ChoiceChips
import com.polylingua.edge.ui.components.EmptyState
import com.polylingua.edge.ui.components.FrostedCard
import com.polylingua.edge.ui.components.GhostButton
import com.polylingua.edge.ui.components.IconCircleButton
import com.polylingua.edge.ui.components.LabeledProgress
import com.polylingua.edge.ui.components.MeterBar
import com.polylingua.edge.ui.components.PillBadge
import com.polylingua.edge.ui.components.PolyIcons
import com.polylingua.edge.ui.components.PrimaryButton
import com.polylingua.edge.ui.components.ScreenHeader
import com.polylingua.edge.ui.components.ScreenPadding
import com.polylingua.edge.ui.components.SectionHeader
import com.polylingua.edge.ui.components.SegmentedTabs
import com.polylingua.edge.ui.navigation.Routes
import com.polylingua.edge.ui.theme.PolyColors
import com.polylingua.edge.ui.theme.PolyType
import kotlinx.coroutines.launch

private data class ModuleTile(val title: String, val subtitle: String, val icon: ImageVector, val accent: Color, val route: String)

@Composable
fun HomeAndSetupScreen(nav: NavHostController, vm: HomeViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val message by vm.message.collectAsStateWithLifecycle()
    val pendingUri by PendingImport.uri.collectAsStateWithLifecycle()
    LaunchedEffect(pendingUri) {
        pendingUri?.let {
            vm.inspectWorkbook(it)
            PendingImport.uri.value = null
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { HeroHeader(onSettings = { nav.navigate(Routes.SETUP) }) }
        item { SegmentedTabs(listOf("A · Sesi Baru", "B · History Vault"), tab, { tab = it }, Modifier.fillMaxWidth()) }
        message?.let { msg ->
            item { Banner(msg, PolyColors.ElectricTeal, icon = PolyIcons.Info) { GhostButton("OK", vm::consumeMessage) } }
        }
        if (tab == 0) newSessionTab(nav, vm) else historyTab(nav, vm)
    }
    ImportDialogs(vm)
}

@Composable
private fun HeroHeader(onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Speaking Mastery Suite", style = PolyType.Hero.copy(fontSize = 26.sp))
            Text("IELTS · CEFR · offline-first on Helio G96", style = PolyType.Caption)
        }
        IconCircleButton(PolyIcons.Sliders, onSettings, contentDescription = "Models & settings")
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.newSessionTab(nav: NavHostController, vm: HomeViewModel) {
    item { ReadinessCard(nav, vm) }
    item {
        val tiles = listOf(
            ModuleTile("60-second Drill", "Stimuler · FC/LR/GRA/P", PolyIcons.Timer, PolyColors.ElectricTeal, Routes.arena("drill")),
            ModuleTile("Full IELTS Mock", "Part 1 → 2 → 3", PolyIcons.Trophy, PolyColors.NeonMintSuccess, Routes.arena("mock")),
            ModuleTile("ELSA Phoneme Lab", "GOP · stress · intonation", PolyIcons.Waveform, PolyColors.AmberWarning, Routes.elsa()),
            ModuleTile("HelloTalk · Tandem", "Humans · LAN · 12 AI natives", PolyIcons.Users, PolyColors.DeepIndigo, Routes.TANDEM),
            ModuleTile("Cake Shadowing", "Dual subs · A-B · WSOLA", PolyIcons.Film, PolyColors.CrimsonAlert, Routes.CAKE),
            ModuleTile("Quiz Arena", "MCQ · timer · flags", PolyIcons.Quiz, PolyColors.ElectricTeal, Routes.quiz("quiz")),
            ModuleTile("Flashcards", "Spaced repetition", PolyIcons.Cards, PolyColors.NeonMintSuccess, Routes.quiz("flash")),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Practice modules", "Tap a tile to start")
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t ->
                        FrostedCard(Modifier.weight(1f).heightIn(min = 112.dp), accent = t.accent, onClick = { nav.navigate(t.route) }) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(t.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                                Icon(t.icon, null, tint = t.accent, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(t.title, style = PolyType.CardTitle)
                            Text(t.subtitle, style = PolyType.Caption, maxLines = 2)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    item { ExcelCard(vm) }
    item { PackagesCard(vm) }
    if (vm.isMiui) item { MiuiCard(vm) }
}

@Composable
private fun ReadinessCard(nav: NavHostController, vm: HomeViewModel) {
    val ready by vm.essentialsReady.collectAsStateWithLifecycle()
    val cases by vm.caseCount.collectAsStateWithLifecycle()
    val settings by vm.settings.settings.collectAsStateWithLifecycle()
    val total = ModelCatalog.ESSENTIALS.size
    FrostedCard(accent = if (ready == total) PolyColors.NeonMintSuccess else PolyColors.AmberWarning) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Offline speech engine", style = PolyType.CardTitle)
                Text(
                    if (ready == total) "All essential packs installed · ${vm.asrBackend.label}"
                    else "$ready/$total essential packs · ${vm.asrBackend.label}",
                    style = PolyType.Caption,
                )
            }
            PillBadge("$cases CASES", PolyColors.ElectricTeal)
        }
        Spacer(Modifier.height(10.dp))
        MeterBar(ready.toFloat(), total.toFloat(), if (ready == total) PolyColors.NeonMintSuccess else PolyColors.AmberWarning)
        if (!com.polylingua.edge.nativebridge.NativeLoader.ensureLoaded()) {
            Spacer(Modifier.height(10.dp))
            Banner(com.polylingua.edge.nativebridge.NativeLoader.loadError ?: "On-device AI runtime unavailable", PolyColors.CrimsonAlert)
        }
        if (vm.asrBackend == AsrBackend.SHERPA_OFFLINE && ready < total) {
            Spacer(Modifier.height(10.dp))
            Text("New: NeMo FastConformer live captions and NVIDIA Parakeet final transcripts are far more accurate than the old 20 M model. Tap Get all in Models & settings.", style = PolyType.Caption.copy(color = PolyColors.AmberWarning))
            Spacer(Modifier.height(6.dp))
            GhostButton("Upgrade speech models", { nav.navigate(Routes.SETUP) }, icon = PolyIcons.Download, color = PolyColors.AmberWarning)
        }
        if (vm.asrBackend != AsrBackend.SHERPA_OFFLINE) {
            Spacer(Modifier.height(10.dp))
            Text("Download the essential bundle (~${ModelCatalog.ESSENTIALS_MB} MB, once) for accurate offline captions, GOP phoneme scoring and neural voices.", style = PolyType.Caption)
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Open model manager", { nav.navigate(Routes.SETUP) }, icon = PolyIcons.Download)
        }
        Spacer(Modifier.height(12.dp))
        Text("Target level", style = PolyType.Caption)
        Spacer(Modifier.height(6.dp))
        ChoiceChips(
            options = CefrLevel.entries.map { "${it.name} · ${it.minBand}-${it.maxBand}" },
            selected = setOf(settings.targetCefr.ordinal),
            onToggle = { vm.setTargetLevel(CefrLevel.entries[it]) },
        )
    }
}

@Composable
private fun ExcelCard(vm: HomeViewModel) {
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::inspectWorkbook) }
    FrostedCard(accent = PolyColors.NeonMintSuccess) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PolyIcons.Sheet, null, tint = PolyColors.NeonMintSuccess, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Master Excel workbook", style = PolyType.CardTitle)
                Text("11- or 16-column schema · up to 1,000 sheets · cached offline", style = PolyType.Caption)
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            "[+ Lampirkan File Master Excel (.xlsx)]",
            { picker.launch(arrayOf(ExcelDynamicIngestor.XLSX_MIME, "application/octet-stream", "application/vnd.ms-excel.sheet.macroEnabled.12")) },
            Modifier.fillMaxWidth(),
            icon = PolyIcons.FileAdd,
            color = PolyColors.NeonMintSuccess,
        )
        Spacer(Modifier.height(8.dp))
        GhostButton(
            if (exporting) "Exporting 1,020 cases…" else "Export built-in 1,020 cases → Documents/",
            vm::exportBuiltIn, Modifier.fillMaxWidth(), icon = PolyIcons.Upload, enabled = !exporting,
        )
    }
}

@Composable
private fun PackagesCard(vm: HomeViewModel) {
    val packages by vm.packages.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    FrostedCard {
        SectionHeader("Pilih Paket / Sheet", "${packages.size} packages cached · tap to expand") {
            IconCircleButton(if (expanded) PolyIcons.ChevronDown else PolyIcons.ChevronRight, { expanded = !expanded }, size = 34.dp)
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                packages.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PolyColors.ObsidianSlate).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = PolyType.Body.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.caseCount} cases · ${if (p.builtIn) "built-in" else p.sourceFile}${if (!p.headerDetected) " · no header row" else ""}", style = PolyType.Caption)
                        }
                        if (!p.builtIn) IconCircleButton(PolyIcons.Trash, { vm.deletePackage(p) }, size = 32.dp, color = PolyColors.CrimsonAlert)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuiCard(vm: HomeViewModel) {
    var exempt by remember { mutableStateOf(vm.batteryExempt()) }
    FrostedCard(accent = PolyColors.AmberWarning) {
        Text("MIUI background protection", style = PolyType.CardTitle)
        Text("Allow Autostart and remove battery restrictions so mock tests, calls and 1 GB downloads are not killed.", style = PolyType.Caption)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Autostart & pop-ups", { vm.openMiuiPermissions() }, Modifier.weight(1f), color = PolyColors.AmberWarning)
            GhostButton(if (exempt) "Battery: unrestricted ✓" else "Battery exemption", { vm.requestBatteryExemption(); exempt = vm.batteryExempt() }, Modifier.weight(1f), color = PolyColors.AmberWarning, enabled = !exempt)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.historyTab(nav: NavHostController, vm: HomeViewModel) {
    item { HistoryHeader(nav, vm) }
    item { HistoryList(nav, vm) }
}

@Composable
private fun HistoryHeader(nav: NavHostController, vm: HomeViewModel) {
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { if (vm.hydrate(uri)) nav.navigate(Routes.DASHBOARD) }
    }
    FrostedCard {
        Text("saved_results/ vault", style = PolyType.CardTitle)
        Text("Every assessment is stored as IELTS_Speaking_Tech_test N.json + .csv + .wav", style = PolyType.Caption)
        Spacer(Modifier.height(10.dp))
        GhostButton("Open a session JSON from storage…", { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.fillMaxWidth(), icon = PolyIcons.FileAdd)
    }
}

@Composable
private fun HistoryList(nav: NavHostController, vm: HomeViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    if (history.isEmpty()) {
        EmptyState(PolyIcons.History, "No saved sessions yet", "Finish a 60-second drill or a full mock and it will appear here.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        history.forEach { e -> HistoryRow(e, onHydrate = { scope.launch { if (vm.hydrate(e)) nav.navigate(Routes.DASHBOARD) } },
            onShare = { try { context.startActivity(vm.shareIntent(e)) } catch (_: ActivityNotFoundException) {} },
            onDelete = { vm.deleteSession(e) }) }
    }
}

@Composable
private fun HistoryRow(e: SessionResultEntity, onHydrate: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val date = remember(e.createdAt) { DateFormat.getMediumDateFormat(context).format(e.createdAt) + " " + DateFormat.getTimeFormat(context).format(e.createdAt) }
    FrostedCard(accent = PolyColors.forBand(e.overallBand)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.stem, style = PolyType.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$date · ${e.wordCount} words · ${e.wpm.toInt()} WPM", style = PolyType.Caption)
                Text(e.prompt, style = PolyType.Caption.copy(color = PolyColors.PureIceWhite), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%.1f".format(e.overallBand), style = PolyType.NumericMedium.copy(color = PolyColors.forBand(e.overallBand)))
                PillBadge(e.cefr, PolyColors.forBand(e.overallBand))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("FC" to e.fc, "LR" to e.lr, "GRA" to e.gra, "P" to e.p).forEach { (k, v) ->
                Column(Modifier.weight(1f)) {
                    Text("$k ${"%.1f".format(v)}", style = PolyType.Pill.copy(color = PolyColors.MutedSlateText))
                    MeterBar(v.toFloat(), 9f, PolyColors.forBand(v), height = 5.dp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Hydrate Diagnostic Dashboard", onHydrate, Modifier.weight(1f), icon = PolyIcons.Chart)
            IconCircleButton(PolyIcons.Share, onShare, size = 40.dp)
            IconCircleButton(PolyIcons.Trash, onDelete, size = 40.dp, color = PolyColors.CrimsonAlert)
        }
    }
}

// ----------------------------------------------------------------------------- import UI

@Composable
private fun ImportDialogs(vm: HomeViewModel) {
    val state by vm.importState.collectAsStateWithLifecycle()
    if (state.inspecting) {
        Dialog(onDismissRequest = {}) {
            FrostedCard { Text("Reading workbook…", style = PolyType.CardTitle); Spacer(Modifier.height(8.dp)); LabeledProgress("Scanning sheets", 0.5f) }
        }
    }
    state.error?.let { err ->
        Dialog(onDismissRequest = vm::dismissImport) {
            FrostedCard(accent = PolyColors.CrimsonAlert) {
                Text("Import failed", style = PolyType.CardTitle)
                Spacer(Modifier.height(6.dp))
                Text(err, style = PolyType.Body)
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Close", vm::dismissImport, color = PolyColors.CrimsonAlert)
            }
        }
    }
    val preview = state.preview ?: return
    Dialog(onDismissRequest = vm::dismissImport, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var query by remember { mutableStateOf("") }
        val filtered = remember(query, preview) {
            preview.sheets.withIndex().filter { query.isBlank() || it.value.name.contains(query, ignoreCase = true) }.map { it.index }
        }
        FrostedCard(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 640.dp)) {
            Text("Pilih Paket / Sheet (Hingga 1.000 Sheet)", style = PolyType.Section)
            Text("${preview.displayName} · ${preview.sheets.size} sheets · ${preview.sizeBytes / 1024} KB", style = PolyType.Caption)
            Spacer(Modifier.height(10.dp))
            when (val p = state.progress) {
                is ImportProgress.Running -> {
                    LabeledProgress("Sheet ${p.sheetIndex + 1}/${p.sheetCount}: ${p.sheetName} · ${p.casesSoFar} cases", p.fraction)
                }
                is ImportProgress.Done -> {
                    Text("✓ ${p.cases} cases cached from ${p.packages} sheet(s).", style = PolyType.Body.copy(color = PolyColors.NeonMintSuccess))
                    if (p.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(p.warnings.take(6).joinToString("\n"), style = PolyType.Caption)
                    }
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Done", vm::dismissImport, Modifier.fillMaxWidth())
                }
                is ImportProgress.Failed -> {
                    Text(p.message, style = PolyType.Body.copy(color = PolyColors.CrimsonAlert))
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Close", vm::dismissImport, Modifier.fillMaxWidth(), color = PolyColors.CrimsonAlert)
                }
                null -> {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search sheet names", style = PolyType.Caption) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PolyColors.ElectricTeal, unfocusedBorderColor = PolyColors.FrostedBorder),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Select ${if (query.isBlank()) "all" else "matches"}", { vm.selectSheets(filtered, true) }, Modifier.weight(1f))
                        GhostButton("Clear", { vm.selectSheets(filtered, false) }, Modifier.weight(1f), color = PolyColors.MutedSlateText)
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 380.dp)) {
                        items(filtered, key = { it }) { idx ->
                            val sheet = preview.sheets[idx]
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.toggleSheet(idx) }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = idx in state.selected, onCheckedChange = { vm.toggleSheet(idx) },
                                    colors = CheckboxDefaults.colors(checkedColor = PolyColors.ElectricTeal, checkmarkColor = PolyColors.AmazeVoidBlack),
                                )
                                Text("${idx + 1}. ${sheet.name}", style = PolyType.Body.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Cancel", vm::dismissImport, Modifier.weight(1f), color = PolyColors.MutedSlateText)
                        PrimaryButton("Import ${state.selected.size} sheet(s)", vm::startImport, Modifier.weight(1.4f), enabled = state.selected.isNotEmpty())
                    }
                }
            }
        }
    }
}

// =================================================================== models & settings

@Composable
fun ModelSetupScreen(onBack: () -> Unit, vm: SetupViewModel = hiltViewModel()) {
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val settings by vm.userSettings.collectAsStateWithLifecycle()
    val roots by vm.roots.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val installedCount = statuses.values.count { it is PackStatus.Installed }
    LaunchedEffect(installedCount) { vm.refresh(rescan = false) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Models & settings", "Offline packs · recognition · voices · AI coach", onBack)
        busy?.let { Banner(it, PolyColors.ElectricTeal, Modifier.padding(horizontal = 16.dp), icon = PolyIcons.Info) }
        LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                FrostedCard {
                    Text("Model storage", style = PolyType.CardTitle)
                    Text("Large packs can live on the microSD card (getExternalFilesDirs).", style = PolyType.Caption)
                    Spacer(Modifier.height(8.dp))
                    roots.forEach { r ->
                        val storage = if (r.removable) ModelStorage.SD_CARD else ModelStorage.INTERNAL
                        val active = settings.modelStorage == storage
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) PolyColors.ElectricTeal.copy(alpha = 0.15f) else PolyColors.ObsidianSlate)
                                .border(1.dp, if (active) PolyColors.ElectricTeal else PolyColors.FrostedBorder, RoundedCornerShape(12.dp))
                                .clickable { vm.setStorage(storage) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (r.removable) PolyIcons.SdCard else PolyIcons.Cpu, null, tint = PolyColors.ElectricTeal, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.label, style = PolyType.Body)
                                Text("${r.freeBytes / 1_048_576} MB free of ${r.totalBytes / 1_073_741_824} GB", style = PolyType.Caption)
                            }
                            if (active) PillBadge("ACTIVE", PolyColors.NeonMintSuccess)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (roots.none { it.removable }) Text("No microSD card detected.", style = PolyType.Caption)
                }
            }
            item {
                FrostedCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Essential offline bundle", style = PolyType.CardTitle)
                            Text("VAD · NeMo live captions · punctuation · Parakeet final transcript · GOP · Kokoro voices (~${ModelCatalog.ESSENTIALS_MB} MB)", style = PolyType.Caption)
                        }
                        PrimaryButton("Get all", vm::downloadEssentials, icon = PolyIcons.Download)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Allow downloads on mobile data", style = PolyType.Body, modifier = Modifier.weight(1f))
                        Switch(settings.cellularDownloadsAllowed, { on -> vm.update { it.copy(cellularDownloadsAllowed = on) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = PolyColors.ElectricTeal))
                    }
                    if (vm.metered() && !settings.cellularDownloadsAllowed) Text("You are on a metered connection: packs over 50 MB wait for Wi-Fi.", style = PolyType.Caption.copy(color = PolyColors.AmberWarning))
                    Text("You get a notification when each pack finishes installing.", style = PolyType.Caption)
                }
            }
            item { SectionHeader("Speech recognition", vm.asrSummary()) }
            item { RecognitionCard(vm, settings, statuses) }
            ModelKind.entries.forEach { kind ->
                val packs = ModelCatalog.ALL.filter { it.kind == kind }
                if (packs.isEmpty()) return@forEach
                item { SectionHeader(kind.title) }
                packs.forEach { pack ->
                    item(key = pack.id) {
                        val st = statuses[pack.id] ?: PackStatus.NotInstalled
                        FrostedCard(accent = if (st is PackStatus.Installed) PolyColors.NeonMintSuccess else null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(pack.title, style = PolyType.CardTitle, modifier = Modifier.weight(1f, fill = false))
                                        if (pack in ModelCatalog.ESSENTIALS) { Spacer(Modifier.width(6.dp)); PillBadge("ESSENTIAL", PolyColors.AmberWarning) }
                                    }
                                    Text("${pack.sizeMb} MB · ${pack.license}", style = PolyType.Caption)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(pack.description, style = PolyType.Caption.copy(color = PolyColors.PureIceWhite))
                            if (pack.id in vm.blockedModels()) {
                                Spacer(Modifier.height(6.dp))
                                Banner("This model crashed while loading on this phone and is disabled.", PolyColors.CrimsonAlert) {
                                    GhostButton("Re-enable", { vm.unblock(pack.id) }, color = PolyColors.CrimsonAlert)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            when (st) {
                                is PackStatus.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("✓ Installed${if (st.onSdCard) " on microSD" else ""} · ${st.diskBytes / 1_048_576} MB", style = PolyType.Caption.copy(color = PolyColors.NeonMintSuccess), modifier = Modifier.weight(1f))
                                    GhostButton("Delete", { vm.delete(pack) }, color = PolyColors.CrimsonAlert)
                                }
                                is PackStatus.Downloading -> Column {
                                    LabeledProgress("${st.bytes / 1_048_576} / ${st.total / 1_048_576} MB · ${st.bytesPerSec / 1024} KB/s", st.fraction)
                                    Spacer(Modifier.height(6.dp))
                                    GhostButton("Cancel", { vm.cancel(pack) }, color = PolyColors.MutedSlateText)
                                }
                                PackStatus.Finishing -> Text("Verifying and installing…", style = PolyType.Caption.copy(color = PolyColors.ElectricTeal))
                                is PackStatus.Queued -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Queued…", style = PolyType.Caption, modifier = Modifier.weight(1f))
                                    GhostButton("Cancel", { vm.cancel(pack) }, color = PolyColors.MutedSlateText)
                                }
                                is PackStatus.Failed -> Column {
                                    Text(st.message, style = PolyType.Caption.copy(color = PolyColors.CrimsonAlert))
                                    Spacer(Modifier.height(6.dp))
                                    PrimaryButton("Retry", { vm.download(pack) }, icon = PolyIcons.Replay)
                                }
                                PackStatus.NotInstalled -> PrimaryButton("Download", { vm.download(pack) }, icon = PolyIcons.Download)
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Voice & accent", "Tap a voice to select and hear it") }
            item { VoicesCard(vm, settings) }
            item { SectionHeader("Audio") }
            item {
                FrostedCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Zero-latency sidetone (3.5 mm)", style = PolyType.Body)
                            Text("Hear yourself through wired headphones while speaking", style = PolyType.Caption)
                        }
                        Switch(settings.sidetoneEnabled, { on -> vm.update { it.copy(sidetoneEnabled = on) } }, colors = SwitchDefaults.colors(checkedTrackColor = PolyColors.ElectricTeal))
                    }
                    Text("Sidetone gain ${"%.2f".format(settings.sidetoneGain)}", style = PolyType.Caption)
                    Slider(settings.sidetoneGain, { g -> vm.update { it.copy(sidetoneGain = g) } }, valueRange = 0.2f..1.5f,
                        colors = SliderDefaults.colors(thumbColor = PolyColors.ElectricTeal, activeTrackColor = PolyColors.ElectricTeal))
                }
            }
            item { SectionHeader("AI coach", "Persona chat · corrections · examiner comments") }
            item { AiCoachCard(vm, settings) }
            item { SectionHeader("Profile & network") }
            item {
                FrostedCard {
                    var name by remember(settings.displayName) { mutableStateOf(settings.displayName) }
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(30) }, singleLine = true, label = { Text("Display name (language exchange)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PolyColors.ElectricTeal, unfocusedBorderColor = PolyColors.FrostedBorder),
                    )
                    Spacer(Modifier.height(6.dp))
                    GhostButton("Save name", { vm.update { it.copy(displayName = name.trim()) } })
                    Spacer(Modifier.height(10.dp))
                    var url by remember(settings.signalingUrl) { mutableStateOf(settings.signalingUrl) }
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, singleLine = true, label = { Text("Signalling relay (MQTT over wss://)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PolyColors.ElectricTeal, unfocusedBorderColor = PolyColors.FrostedBorder),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Save relay", { vm.update { it.copy(signalingUrl = url.trim()) } })
                        GhostButton("Reset", { vm.update { it.copy(signalingUrl = com.polylingua.edge.core.UserSettings.DEFAULT_SIGNALING_URL) } }, color = PolyColors.MutedSlateText)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("CPU: ${CoreExecutors.topology.summary} · inference threads ${CoreExecutors.inferenceThreads}", style = PolyType.Caption)
                }
            }
            item { SectionHeader("Diagnostics", "Send this log if something does not work") }
            item { DiagnosticsCard(vm) }
        }
    }
}

@Composable
private fun RecognitionCard(vm: SetupViewModel, settings: com.polylingua.edge.core.UserSettings, statuses: Map<String, PackStatus>) {
    FrostedCard {
        val live = ModelCatalog.STREAMING_ASR.filter { statuses[it.id] is PackStatus.Installed }
        Text("Live captions", style = PolyType.Body)
        Text("What you see while speaking (also word timing for fluency).", style = PolyType.Caption)
        Spacer(Modifier.height(6.dp))
        val liveOptions = listOf("" to "Auto (most accurate)") + live.map { it.id to it.title.substringBefore(" (") }
        ChoiceChips(liveOptions.map { it.second }, setOf(liveOptions.indexOfFirst { it.first == settings.liveAsrPackId }.coerceAtLeast(0)),
            { i -> vm.update { it.copy(liveAsrPackId = liveOptions[i].first) } })
        if (live.isEmpty()) Text("No live recogniser installed yet - download NeMo FastConformer below.", style = PolyType.Caption.copy(color = PolyColors.AmberWarning))
        Spacer(Modifier.height(12.dp))
        Text("Final transcript (used for scoring)", style = PolyType.Body)
        Text("Re-reads each whole answer after you stop - much more accurate than live captions.", style = PolyType.Caption)
        Spacer(Modifier.height(6.dp))
        val finals = ModelCatalog.SECOND_PASS_ASR.filter { statuses[it.id] is PackStatus.Installed }
        val finalOptions = listOf("" to "Auto") + finals.map { it.id to it.title.substringBefore(" (") } +
            listOf(com.polylingua.edge.core.UserSettings.FINAL_ASR_GEMINI to "Gemini (cloud)", com.polylingua.edge.core.UserSettings.FINAL_ASR_OFF to "Off")
        ChoiceChips(finalOptions.map { it.second }, setOf(finalOptions.indexOfFirst { it.first == settings.finalAsrPackId }.coerceAtLeast(0)),
            { i -> vm.update { it.copy(finalAsrPackId = finalOptions[i].first) } })
        if (settings.finalAsrPackId == com.polylingua.edge.core.UserSettings.FINAL_ASR_GEMINI && !vm.hasKey(com.polylingua.edge.core.SecureKeyStore.Slot.GEMINI)) {
            Text("Add a Gemini API key in AI coach below. Offline Parakeet is used when there is no internet.", style = PolyType.Caption.copy(color = PolyColors.AmberWarning))
        }
        Spacer(Modifier.height(12.dp))
        Text("End of turn in hands-free conversations: ${"%.1f".format(settings.turnEndSilenceSec)} s of silence", style = PolyType.Caption)
        Slider(settings.turnEndSilenceSec, { v -> vm.update { it.copy(turnEndSilenceSec = (v * 10).toInt() / 10f) } }, valueRange = 0.8f..3.5f,
            colors = SliderDefaults.colors(thumbColor = PolyColors.ElectricTeal, activeTrackColor = PolyColors.ElectricTeal))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Microphone auto-gain", style = PolyType.Body)
                Text("Evens out quiet/loud speech and removes rumble before recognition", style = PolyType.Caption)
            }
            Switch(settings.micAutoGain, { on -> vm.update { it.copy(micAutoGain = on) } }, colors = SwitchDefaults.colors(checkedTrackColor = PolyColors.ElectricTeal))
        }
    }
}

@Composable
private fun VoicesCard(vm: SetupViewModel, settings: com.polylingua.edge.core.UserSettings) {
    val voices by vm.voices.collectAsStateWithLifecycle()
    val missing by vm.missingVoices.collectAsStateWithLifecycle()
    val health by vm.voiceHealth.collectAsStateWithLifecycle()
    val sys by vm.systemTts.collectAsStateWithLifecycle()
    FrostedCard {
        Text("Model-answer accent", style = PolyType.Caption)
        Spacer(Modifier.height(6.dp))
        ChoiceChips(AccentPreference.entries.map { it.label }, setOf(settings.accent.ordinal), { vm.setAccent(AccentPreference.entries[it]) })
        Spacer(Modifier.height(10.dp))
        Text(
            if (sys.ready) "Android engine: ${sys.engineLabel.ifBlank { "unknown" }} · ${sys.voiceCount} offline voices"
            else sys.error ?: "Android engine: checking…",
            style = PolyType.Caption.copy(color = if (sys.ready) PolyColors.MutedSlateText else PolyColors.AmberWarning),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Test all voices", vm::testAllVoices, Modifier.weight(1f), icon = PolyIcons.Speaker)
            GhostButton("Android TTS settings", vm::openTtsSettings, Modifier.weight(1f), icon = PolyIcons.Sliders, color = PolyColors.MutedSlateText)
        }
        Spacer(Modifier.height(10.dp))
        voices.forEach { v ->
            val active = settings.ttsVoiceId == v.id
            val h = health[v.id]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (active) PolyColors.ElectricTeal.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { vm.update { it.copy(ttsVoiceId = v.id) }; vm.previewVoice(v) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PolyIcons.Speaker, null, tint = if (v.isNeural) PolyColors.NeonMintSuccess else PolyColors.MutedSlateText, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.label, style = PolyType.Body.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when (h) {
                        is com.polylingua.edge.speech.VoiceHealth.Ok -> Text("✓ works${if (h.millis > 0) " · ${h.millis} ms" else ""}", style = PolyType.Caption.copy(color = PolyColors.NeonMintSuccess, fontSize = 11.sp))
                        is com.polylingua.edge.speech.VoiceHealth.Failed -> Text("✗ ${h.reason}", style = PolyType.Caption.copy(color = PolyColors.CrimsonAlert, fontSize = 11.sp), maxLines = 2)
                        null -> {}
                    }
                }
                PillBadge(if (v.isNeural) "NEURAL ${v.accent}" else "SYSTEM ${v.accent}", if (v.isNeural) PolyColors.NeonMintSuccess else PolyColors.MutedSlateText)
            }
        }
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Not installed on this phone: ${missing.joinToString { it.accent }}", style = PolyType.Caption.copy(color = PolyColors.AmberWarning))
            Text("Install the voice data (free) to use these accents for personas like Chloe (AU) or Budi (ID).", style = PolyType.Caption)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Install voice data", vm::installVoiceData, Modifier.weight(1f), icon = PolyIcons.Download, color = PolyColors.AmberWarning)
                GhostButton("Google TTS", vm::getGoogleTts, Modifier.weight(1f), icon = PolyIcons.Globe, color = PolyColors.MutedSlateText)
            }
        }
    }
}

@Composable
private fun AiCoachCard(vm: SetupViewModel, settings: com.polylingua.edge.core.UserSettings) {
    val health by vm.aiHealth.collectAsStateWithLifecycle()
    val llmState by vm.llmState.collectAsStateWithLifecycle()
    val revision by vm.keyRevision.collectAsStateWithLifecycle()
    val kinds = com.polylingua.edge.core.AiProviderKind.entries
    FrostedCard {
        Text("Provider", style = PolyType.Body)
        Spacer(Modifier.height(6.dp))
        ChoiceChips(kinds.map { it.label.substringBefore(" (") }, setOf(settings.aiProvider.ordinal), { vm.setProvider(kinds[it]) })
        Spacer(Modifier.height(6.dp))
        Text(
            "Cloud providers need their own API key - a Claude Pro or Google AI Pro chat subscription does not include API access. " +
                "Keys are encrypted with the Android Keystore and never leave this phone except to call that provider.",
            style = PolyType.Caption,
        )
        Spacer(Modifier.height(10.dp))
        when (settings.aiProvider) {
            com.polylingua.edge.core.AiProviderKind.GEMINI -> {
                KeyField(vm, com.polylingua.edge.core.SecureKeyStore.Slot.GEMINI, revision)
                ModelField("Gemini model", settings.geminiModel, listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-2.5-flash")) { m -> vm.update { it.copy(geminiModel = m) } }
                Text("Free key (with daily limits) at aistudio.google.com/apikey. Gemini can also transcribe your answers (Speech recognition → Final transcript → Gemini).", style = PolyType.Caption)
                GhostButton("Get a Gemini API key", { vm.openUrl("https://aistudio.google.com/apikey") }, icon = PolyIcons.Globe)
            }
            com.polylingua.edge.core.AiProviderKind.CLAUDE -> {
                KeyField(vm, com.polylingua.edge.core.SecureKeyStore.Slot.CLAUDE, revision)
                ModelField("Claude model", settings.claudeModel, listOf("claude-haiku-5-5", "claude-sonnet-5-5")) { m -> vm.update { it.copy(claudeModel = m) } }
                Text("Create a key in the Claude Console (pay-as-you-go API billing, separate from Claude Pro). Haiku 5.5 is the fast, low-cost choice.", style = PolyType.Caption)
                GhostButton("Open Claude Console", { vm.openUrl("https://console.anthropic.com/settings/keys") }, icon = PolyIcons.Globe)
            }
            com.polylingua.edge.core.AiProviderKind.OPENAI_COMPAT -> {
                var base by remember(settings.openAiBaseUrl) { mutableStateOf(settings.openAiBaseUrl) }
                OutlinedTextField(base, { base = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Base URL (…/v1)") }, placeholder = { Text("https://openrouter.ai/api/v1 or http://192.168.1.5:1234/v1", style = PolyType.Caption) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PolyColors.ElectricTeal, unfocusedBorderColor = PolyColors.FrostedBorder))
                GhostButton("Save URL", { vm.update { it.copy(openAiBaseUrl = base) } })
                ModelField("Model", settings.openAiModel, emptyList()) { m -> vm.update { it.copy(openAiModel = m) } }
                KeyField(vm, com.polylingua.edge.core.SecureKeyStore.Slot.OPENAI, revision, optional = true)
                Text("Works with OpenRouter, Groq, Together, or a model running on your PC (LM Studio / Ollama on the same Wi-Fi).", style = PolyType.Caption)
            }
            com.polylingua.edge.core.AiProviderKind.ON_DEVICE -> {
                Text(
                    when (val s = llmState) {
                        is LlmState.Ready -> "Loaded: ${s.pack}"
                        is LlmState.Loading -> "Loading ${s.pack}…"
                        is LlmState.Error -> s.message
                        LlmState.NotInstalled -> "No GGUF model installed (download Qwen2.5 1.5B or SmolLM2 in the Offline AI coach section above)"
                        LlmState.Unloaded -> "Loads on first use (2 threads on Cortex-A76)"
                    },
                    style = PolyType.Caption,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton("Test ${settings.aiProvider.label.substringBefore(" (")}", { vm.testProvider(settings.aiProvider) }, icon = PolyIcons.Bolt)
            Spacer(Modifier.width(8.dp))
            health[settings.aiProvider]?.let { h ->
                Text((if (h.ok) "✓ " else "✗ ") + h.message, style = PolyType.Caption.copy(color = if (h.ok) PolyColors.NeonMintSuccess else PolyColors.CrimsonAlert), maxLines = 3)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Offline fallback", style = PolyType.Body)
                Text("Use the on-device model when the cloud is unreachable", style = PolyType.Caption)
            }
            Switch(settings.aiOfflineFallback, { on -> vm.update { it.copy(aiOfflineFallback = on) } }, colors = SwitchDefaults.colors(checkedTrackColor = PolyColors.ElectricTeal))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use on-device LLM", style = PolyType.Body)
                Text("Qwen2.5 1.5B / SmolLM2 via llama.cpp (offline)", style = PolyType.Caption)
            }
            Switch(settings.useLlmCoach, { on -> vm.update { it.copy(useLlmCoach = on) } }, colors = SwitchDefaults.colors(checkedTrackColor = PolyColors.ElectricTeal))
        }
        val llmPacks = listOf(ModelCatalog.LLM_QWEN, ModelCatalog.LLM_SMOL)
        ChoiceChips(llmPacks.map { it.title }, setOf(llmPacks.indexOfFirst { it.id == settings.llmPackId }.coerceAtLeast(0)),
            { i -> vm.update { it.copy(llmPackId = llmPacks[i].id) } })
        Spacer(Modifier.height(6.dp))
        GhostButton("Load on-device model now", vm::loadLlm, icon = PolyIcons.Cpu)
    }
}

@Composable
private fun KeyField(vm: SetupViewModel, slot: com.polylingua.edge.core.SecureKeyStore.Slot, revision: Int, optional: Boolean = false) {
    var key by remember(slot, revision) { mutableStateOf("") }
    val saved = remember(slot, revision) { vm.maskedKey(slot) }
    OutlinedTextField(
        key, { key = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(slot.label + if (optional) " (optional)" else "") },
        placeholder = { Text(if (saved.isNotBlank()) "Saved: $saved" else "Paste your key", style = PolyType.Caption) },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PolyColors.ElectricTeal, unfocusedBorderColor = PolyColors.FrostedBorder),
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton("Save key", { vm.saveKey(slot, key); key = "" }, enabled = key.length >= 8, icon = PolyIcons.Lock)
        if (saved.isNotBlank()) GhostButton("Remove", { vm.saveKey(slot, "") }, color = PolyColors.CrimsonAlert)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ModelField(label: String, value: String, presets: List<String>, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    if (presets.isNotEmpty()) {
        ChoiceChips(presets, setOf(presets.indexOf(value)).filter { it >= 0 }.toSet(), { i -> onSave(presets[i]) })
        Spacer(Modifier.height(6.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text(label) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PolyColors.ElectricTeal, unfocusedBorderColor = PolyColors.FrostedBorder))
        Spacer(Modifier.width(6.dp))
        GhostButton("Use", { if (text.isNotBlank()) onSave(text) })
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DiagnosticsCard(vm: SetupViewModel) {
    val lines by vm.diagLines.collectAsStateWithLifecycle()
    FrostedCard {
        Text("$lines log lines recorded this session (downloads, voices, recognisers, AI calls). API keys are never logged.", style = PolyType.Caption)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Share log", vm::shareDiagnostics, Modifier.weight(1f), icon = PolyIcons.Share)
            GhostButton("Copy", vm::copyDiagnostics, icon = PolyIcons.Cards)
            GhostButton("Clear", vm::clearDiagnostics, color = PolyColors.MutedSlateText)
        }
    }
}
