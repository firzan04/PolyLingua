package com.polylingua.edge.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.polylingua.edge.domain.model.ModuleType
import com.polylingua.edge.domain.model.PracticeCase
import com.polylingua.edge.ui.components.Banner
import com.polylingua.edge.ui.components.ChoiceChips
import com.polylingua.edge.ui.components.EmptyState
import com.polylingua.edge.ui.components.FrostedCard
import com.polylingua.edge.ui.components.GhostButton
import com.polylingua.edge.ui.components.IconCircleButton
import com.polylingua.edge.ui.components.MeterBar
import com.polylingua.edge.ui.components.PillBadge
import com.polylingua.edge.ui.components.PolyIcons
import com.polylingua.edge.ui.components.PrimaryButton
import com.polylingua.edge.ui.components.ScoreRing
import com.polylingua.edge.ui.components.ScreenHeader
import com.polylingua.edge.ui.components.ScreenPadding
import com.polylingua.edge.ui.components.SectionHeader
import com.polylingua.edge.ui.components.StatTile
import com.polylingua.edge.ui.components.formatHms
import com.polylingua.edge.ui.theme.PolyColors
import com.polylingua.edge.ui.theme.PolyMotion
import com.polylingua.edge.ui.theme.PolyType

@Composable
fun QuizAndFlashcardArenaScreen(nav: NavHostController, vm: QuizViewModel = hiltViewModel()) {
    val quiz by vm.quiz.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            if (vm.flashMode) "Flashcards" else "Quiz Arena",
            if (vm.flashMode) "SM-2 spaced repetition · tap card to flip" else "A-D MCQ · explanations · HH:MM:SS timer",
            onBack = { nav.popBackStack() },
        ) {
            if (!vm.flashMode && quiz.phase == QuizPhase.RUNNING) {
                val t = if (quiz.limitMs > 0) quiz.remainingMs else quiz.elapsedMs
                PillBadge("⏱ ${formatHms(t / 1000)}", if (quiz.limitMs > 0 && quiz.remainingMs < 60_000) PolyColors.CrimsonAlert else PolyColors.ElectricTeal, filled = true)
            }
        }
        if (vm.flashMode) FlashcardView(vm) else when (quiz.phase) {
            QuizPhase.SETUP -> QuizSetupView(vm)
            QuizPhase.RUNNING -> QuizRunningView(vm)
            QuizPhase.RESULTS -> QuizResultsView(vm)
        }
    }
}

@Composable
private fun QuizSetupView(vm: QuizViewModel) {
    val setup by vm.setup.collectAsStateWithLifecycle()
    val packages by vm.packages.collectAsStateWithLifecycle()
    val quiz by vm.quiz.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        quiz.message?.let { item { Banner(it, PolyColors.AmberWarning) } }
        item {
            FrostedCard {
                Text("Questions", style = PolyType.CardTitle)
                Spacer(Modifier.height(6.dp))
                val counts = listOf(10, 20, 40, 100)
                ChoiceChips(counts.map { "$it" }, setOf(counts.indexOf(setup.count).coerceAtLeast(0)), { vm.setCount(counts[it]) })
                Spacer(Modifier.height(12.dp))
                Text("Time limit", style = PolyType.CardTitle)
                Spacer(Modifier.height(6.dp))
                val limits = listOf(0, 30, 60, 90)
                ChoiceChips(listOf("Stopwatch", "30 s / question", "60 s / question", "90 s / question"),
                    setOf(limits.indexOf(setup.secondsPerQuestion).coerceAtLeast(0)), { vm.setSecondsPerQuestion(limits[it]) })
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Shuffle questions & options", style = PolyType.Body, modifier = Modifier.weight(1f))
                    Switch(setup.shuffle, vm::setShuffle, colors = SwitchDefaults.colors(checkedTrackColor = PolyColors.ElectricTeal))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Instant feedback with explanations", style = PolyType.Body, modifier = Modifier.weight(1f))
                    Switch(setup.instantFeedback, vm::setInstant, colors = SwitchDefaults.colors(checkedTrackColor = PolyColors.ElectricTeal))
                }
            }
        }
        item {
            FrostedCard {
                SectionHeader("Packages", if (setup.selectedPackages.isEmpty()) "None selected = all quiz questions near your level" else "${setup.selectedPackages.size} selected")
                packages.forEach { p ->
                    val active = p.id in setup.selectedPackages
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (active) PolyColors.ElectricTeal.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { vm.togglePackage(p.id) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (active) "☑" else "☐", style = PolyType.Body.copy(color = PolyColors.ElectricTeal))
                        Spacer(Modifier.width(8.dp))
                        Text(p.name, style = PolyType.Body.copy(fontSize = 13.sp), modifier = Modifier.weight(1f))
                        Text("${p.caseCount}", style = PolyType.Numeric.copy(fontSize = 12.sp, color = PolyColors.MutedSlateText))
                    }
                }
            }
        }
        item { PrimaryButton("Start quiz", { vm.startQuiz() }, Modifier.fillMaxWidth(), icon = PolyIcons.Play) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuizRunningView(vm: QuizViewModel) {
    val quiz by vm.quiz.collectAsStateWithLifecycle()
    val setup by vm.setup.collectAsStateWithLifecycle()
    val qi = quiz.current ?: return
    LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Question ${quiz.index + 1} / ${quiz.items.size}", style = PolyType.Caption, modifier = Modifier.weight(1f))
                PillBadge("${qi.case.cefr} · ${qi.case.topic.ifBlank { qi.case.sheetName }}", PolyColors.MutedSlateText)
                Spacer(Modifier.width(8.dp))
                IconCircleButton(PolyIcons.Flag, vm::toggleFlag, size = 36.dp, color = if (qi.flagged) PolyColors.AmberWarning else PolyColors.MutedSlateText)
            }
            Spacer(Modifier.height(6.dp))
            MeterBar((quiz.index + 1).toFloat(), quiz.items.size.toFloat(), PolyColors.ElectricTeal, height = 6.dp)
        }
        item {
            FrostedCard(accent = if (qi.flagged) PolyColors.AmberWarning else PolyColors.ElectricTeal) {
                Text(qi.case.prompt, style = PolyType.Body.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(4.dp))
                GhostButton("Read aloud", { vm.speak(qi.case.prompt) }, icon = PolyIcons.Speaker, color = PolyColors.MutedSlateText)
            }
        }
        qi.order.forEachIndexed { pos, original ->
            item(key = "opt-${quiz.index}-$pos") {
                val reveal = qi.answered && setup.instantFeedback
                val isChosen = qi.chosen == original
                val isCorrect = original == qi.correctIndex
                val color = when {
                    reveal && isCorrect -> PolyColors.NeonMintSuccess
                    reveal && isChosen -> PolyColors.CrimsonAlert
                    isChosen -> PolyColors.ElectricTeal
                    else -> PolyColors.FrostedBorder
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = if (isChosen || (reveal && isCorrect)) 0.14f else 0f))
                        .border(1.5.dp, color, RoundedCornerShape(14.dp)).clickable { vm.choose(pos) }.padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                            Text(('A' + pos).toString(), style = PolyType.CardTitle)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(qi.case.options[original], style = PolyType.Body.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
                    }
                    if (reveal && (isChosen || isCorrect) && qi.case.explanationFor(original).isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(qi.case.explanationFor(original), style = PolyType.Caption.copy(color = PolyColors.PureIceWhite))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("◀ Prev", vm::prev, Modifier.weight(1f), enabled = quiz.index > 0)
                if (quiz.index < quiz.items.lastIndex) PrimaryButton("Next ▶", vm::next, Modifier.weight(1f))
                else PrimaryButton("Finish", vm::finishQuiz, Modifier.weight(1f), color = PolyColors.NeonMintSuccess)
            }
        }
        item {
            FrostedCard {
                Text("Navigator", style = PolyType.Caption)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    quiz.items.forEachIndexed { i, q ->
                        val c = when {
                            q.flagged -> PolyColors.AmberWarning
                            q.answered && setup.instantFeedback -> if (q.isCorrect) PolyColors.NeonMintSuccess else PolyColors.CrimsonAlert
                            q.answered -> PolyColors.ElectricTeal
                            else -> PolyColors.FrostedBorder
                        }
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = if (i == quiz.index) 0.5f else 0.15f))
                                .border(1.dp, c, RoundedCornerShape(8.dp)).clickable { vm.goTo(i) },
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", style = PolyType.Numeric.copy(fontSize = 12.sp)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                GhostButton("Submit now (${quiz.items.count { it.answered }}/${quiz.items.size} answered)", vm::finishQuiz, Modifier.fillMaxWidth(), color = PolyColors.MutedSlateText)
            }
        }
    }
}

@Composable
private fun QuizResultsView(vm: QuizViewModel) {
    val quiz by vm.quiz.collectAsStateWithLifecycle()
    val pct = if (quiz.items.isEmpty()) 0.0 else 100.0 * quiz.score / quiz.items.size
    LazyColumn(contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FrostedCard(accent = PolyColors.forScore(pct)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(pct, "score")
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${quiz.score} / ${quiz.items.size} correct", style = PolyType.Section)
                        Text("Time ${formatHms(quiz.elapsedMs / 1000)} · ${quiz.items.count { it.flagged }} flagged", style = PolyType.Caption)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Correct", "${quiz.score}", Modifier.weight(1f), color = PolyColors.NeonMintSuccess)
                    StatTile("Wrong", "${quiz.items.count { it.answered && !it.isCorrect }}", Modifier.weight(1f), color = PolyColors.CrimsonAlert)
                    StatTile("Skipped", "${quiz.items.count { !it.answered }}", Modifier.weight(1f), color = PolyColors.MutedSlateText)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Retry wrong & flagged", vm::retryMissed, Modifier.weight(1f), icon = PolyIcons.Replay)
                    GhostButton("New quiz", vm::backToSetup)
                }
            }
        }
        itemsIndexed(quiz.items) { i, q ->
            FrostedCard(accent = if (q.isCorrect) PolyColors.NeonMintSuccess else PolyColors.CrimsonAlert) {
                Text("${i + 1}. ${q.case.prompt}", style = PolyType.Body.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(6.dp))
                Text("✓ ${q.case.options[q.correctIndex]}", style = PolyType.Body.copy(color = PolyColors.NeonMintSuccess))
                if (q.chosen != null && !q.isCorrect) Text("✗ Your answer: ${q.case.options[q.chosen]}", style = PolyType.Body.copy(color = PolyColors.CrimsonAlert))
                if (q.chosen == null) Text("— not answered", style = PolyType.Caption)
                val why = q.case.explanationFor(q.correctIndex)
                if (why.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(why, style = PolyType.Caption) }
                if (q.chosen != null && !q.isCorrect) {
                    val wrongWhy = q.case.explanationFor(q.chosen)
                    if (wrongWhy.isNotBlank()) Text(wrongWhy, style = PolyType.Caption.copy(color = PolyColors.AmberWarning))
                }
            }
        }
    }
}

@Composable
private fun FlashcardView(vm: QuizViewModel) {
    val f by vm.flash.collectAsStateWithLifecycle()
    if (f.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = PolyColors.ElectricTeal) }
        return
    }
    val card = f.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Due", "${f.dueCount}", Modifier.weight(1f), color = PolyColors.AmberWarning)
            StatTile("New", "${f.newCount}", Modifier.weight(1f), color = PolyColors.ElectricTeal)
            StatTile("Reviewed", "${f.reviewed}", Modifier.weight(1f), color = PolyColors.NeonMintSuccess)
        }
        if (card == null) {
            EmptyState(PolyIcons.Check, "All caught up!", "No cards are due right now. Come back later or load a fresh batch.") {
                PrimaryButton("Load more cards", vm::reloadFlashcards, icon = PolyIcons.Replay)
            }
            return@Column
        }
        val rotation by animateFloatAsState(if (f.flipped) 180f else 0f, PolyMotion.spring(), label = "flip")
        Box(
            Modifier.fillMaxWidth().heightIn(min = 280.dp).graphicsLayer { rotationY = rotation; cameraDistance = 14f * density }
                .clip(RoundedCornerShape(24.dp)).background(PolyColors.FrostedSurfaceSolid)
                .border(1.dp, if (f.flipped) PolyColors.NeonMintSuccess else PolyColors.ElectricTeal, RoundedCornerShape(24.dp))
                .clickable(onClick = vm::flip).padding(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (rotation <= 90f) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PillBadge("${card.cefr} · ${card.topic}", PolyColors.MutedSlateText)
                    Spacer(Modifier.height(14.dp))
                    Text(card.prompt, style = PolyType.Section.copy(fontSize = 20.sp), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text("Tap to reveal", style = PolyType.Caption)
                }
            } else {
                Column(Modifier.graphicsLayer { rotationY = 180f }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(backTitle(card), style = PolyType.Section.copy(fontSize = 20.sp, color = PolyColors.NeonMintSuccess), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    backNotes(card).forEach { Text(it, style = PolyType.Caption.copy(color = PolyColors.PureIceWhite), textAlign = TextAlign.Center) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Hear front", { vm.speak(card.prompt) }, Modifier.weight(1f), icon = PolyIcons.Speaker)
            GhostButton("Hear answer", { vm.speak(backTitle(card)) }, Modifier.weight(1f), icon = PolyIcons.Speaker, color = PolyColors.NeonMintSuccess)
        }
        if (f.flipped) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Again", { vm.grade(1) }, Modifier.weight(1f), color = PolyColors.CrimsonAlert)
                PrimaryButton("Hard", { vm.grade(3) }, Modifier.weight(1f), color = PolyColors.AmberWarning)
                PrimaryButton("Good", { vm.grade(4) }, Modifier.weight(1f), color = PolyColors.ElectricTeal)
                PrimaryButton("Easy", { vm.grade(5) }, Modifier.weight(1f), color = PolyColors.NeonMintSuccess)
            }
        } else {
            PrimaryButton("Show answer", vm::flip, Modifier.fillMaxWidth(), icon = PolyIcons.Eye)
        }
        Text("Card ${f.position + 1} of ${f.queue.size}${if (f.again > 0) " · ${f.again} to repeat" else ""}", style = PolyType.Caption)
    }
}

private fun backTitle(c: PracticeCase): String =
    if (c.module == ModuleType.QUIZ_MCQ) c.options.getOrElse(c.correctOptionIndex()) { c.colG } else c.colG.ifBlank { c.colD }

private fun backNotes(c: PracticeCase): List<String> =
    if (c.module == ModuleType.QUIZ_MCQ) listOf(c.explanationFor(c.correctOptionIndex())).filter { it.isNotBlank() }
    else listOf(c.colH, c.colI, c.colK).filter { it.isNotBlank() }
