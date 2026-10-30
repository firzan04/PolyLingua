package com.polylingua.edge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.polylingua.edge.domain.PhoneticArticulationDatabase
import com.polylingua.edge.domain.model.PhonemeScore
import com.polylingua.edge.ui.theme.PolyColors
import com.polylingua.edge.ui.theme.PolyType

/**
 * ELSA-style remediation modal for one phoneme: what was detected vs. expected, articulation
 * instructions (tongue/lips/voicing), the Indonesian-L1 note, examples and tappable minimal pairs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemediationSheet(
    word: String,
    phoneme: PhonemeScore,
    onDismiss: () -> Unit,
    onSpeak: (String) -> Unit,
    onDrill: ((String) -> Unit)? = null,
) {
    val info = PhoneticArticulationDatabase.lookup(phoneme.arpabet)
    val libraryMode = phoneme.score < 0
    val headline = PhoneticArticulationDatabase.remediationHeadline(phoneme.arpabet, phoneme.detectedArpabet)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FrostedCard(Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 620.dp), accent = if (libraryMode) PolyColors.ElectricTeal else gradeColor(phoneme.grade)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("/${info?.ipa ?: phoneme.ipa}/", style = PolyType.Hero.copy(fontSize = 34.sp, color = if (libraryMode) PolyColors.ElectricTeal else gradeColor(phoneme.grade)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        if (libraryMode) {
                            Text(info?.kind?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Phoneme", style = PolyType.CardTitle)
                            Text("ARPAbet ${phoneme.arpabet}", style = PolyType.Caption)
                        } else {
                            Text("in \"$word\"", style = PolyType.CardTitle)
                            Text("GOP score ${phoneme.score}/100 · ${phoneme.grade.name.lowercase()}", style = PolyType.Caption)
                        }
                    }
                    IconCircleButton(PolyIcons.Close, onDismiss, size = 36.dp)
                }
                if (!libraryMode) {
                    Spacer(Modifier.height(10.dp))
                    Banner(headline, gradeColor(phoneme.grade), icon = PolyIcons.Target)
                }
                if (info != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(info.description.replaceFirstChar { it.uppercase() }, style = PolyType.CardTitle)
                    Spacer(Modifier.height(4.dp))
                    Text(info.instruction, style = PolyType.Body)
                    Spacer(Modifier.height(10.dp))
                    Text("🇮🇩 Catatan untuk penutur Bahasa Indonesia", style = PolyType.Caption.copy(color = PolyColors.AmberWarning))
                    Text(info.indonesianL1Note, style = PolyType.Body.copy(fontSize = 13.sp))
                    Spacer(Modifier.height(12.dp))
                    Text("Examples (tap to hear)", style = PolyType.Caption)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(word).filter { it.isNotBlank() } + info.examples).distinct().forEach { ex ->
                            Row(
                                Modifier.clip(RoundedCornerShape(10.dp)).background(PolyColors.ObsidianSlate).border(1.dp, PolyColors.FrostedBorder, RoundedCornerShape(10.dp))
                                    .clickable { onSpeak(ex) }.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(PolyIcons.Speaker, null, tint = PolyColors.ElectricTeal, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(ex, style = PolyType.Body.copy(fontSize = 13.sp))
                            }
                        }
                    }
                    if (info.minimalPairs.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Minimal pairs", style = PolyType.Caption)
                        Spacer(Modifier.height(6.dp))
                        info.minimalPairs.forEach { (a, b) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostButton(a, { onSpeak(a) }, Modifier.weight(1f), icon = PolyIcons.Speaker)
                                Text("vs", style = PolyType.Caption, modifier = Modifier.align(Alignment.CenterVertically))
                                GhostButton(b, { onSpeak(b) }, Modifier.weight(1f), icon = PolyIcons.Speaker, color = PolyColors.AmberWarning)
                            }
                        }
                    }
                }
                if (onDrill != null) {
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Drill this sound now", { onDrill(phoneme.arpabet) }, Modifier.fillMaxWidth(), icon = PolyIcons.Mic)
                }
            }
        }
    }
}
