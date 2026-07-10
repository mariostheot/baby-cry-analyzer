package com.babycry.analyzer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.audio.SoundType
import com.babycry.analyzer.ui.i18n.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SootheScreen(viewModel: CryViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.soothing.collectAsState()
    var minutes by remember { mutableStateOf(30) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            tr("Ηρέμησε το μωρό"),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Text(
            tr("Απαλοί ήχοι που θυμίζουν το περιβάλλον της κοιλιάς και βοηθούν το μωρό να ηρεμήσει. Ρύθμισε την ένταση με τα κουμπιά έντασης του τηλεφώνου."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))

        state.playing?.let { playing ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "${playing.emoji} ${tr(playing.displayName)}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.remainingSec > 0) "${tr("Απομένει:")} ${formatMmSs(state.remainingSec)}"
                        else tr("Παίζει (χωρίς χρονικό όριο)"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.stopSoothing() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(tr("Σταμάτα"))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text(tr("Χρονόμετρο"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 60, 0).forEach { m ->
                FilterChip(
                    selected = minutes == m,
                    onClick = {
                        minutes = m
                        state.playing?.let { viewModel.playSoothing(it, m) }
                    },
                    label = { Text(if (m == 0) "∞" else "$m′") },
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        Text(tr("Ήχοι"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SoundType.entries.forEach { type ->
            val active = state.playing == type
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.playSoothing(type, minutes) },
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(type.emoji, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            tr(type.displayName),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            tr(type.description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    if (active) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = tr("Παίζει τώρα"))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatMmSs(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
