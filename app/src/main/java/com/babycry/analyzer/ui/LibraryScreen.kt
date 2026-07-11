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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babycry.analyzer.data.CryEvent
import com.babycry.analyzer.model.CryReason
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A dedicated "database" page for the parent's own recordings: every confirmed cry that still
 * has a saved clip, showing the confirmed reason + when it happened, with a play button to
 * hear it again. The controls that used to live under Settings (save-recordings toggle,
 * how much is stored, and export) live here too, so everything about saved cries is in one
 * place. Kept fully on-device.
 */
@Composable
fun LibraryScreen(
    viewModel: CryViewModel,
    onExportDataset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = viewModel.labels
    val recents by viewModel.recentEvents.collectAsState()
    val language by viewModel.language.collectAsState()
    val saveClips by viewModel.saveClipsEnabled.collectAsState()
    var clips by remember { mutableStateOf<List<CryEvent>>(emptyList()) }
    var dataset by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    // Reload whenever events change (new confirmation, deletion), the toggle flips, or language changes.
    LaunchedEffect(recents, language, saveClips) {
        clips = viewModel.libraryEvents()
        dataset = viewModel.datasetInfo()
    }

    val fmt = remember(language) {
        SimpleDateFormat(
            "EEE d/M · HH:mm",
            if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el"),
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                Text(tr("Αποθηκευμένα κλάματα"), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    tr("Οι επιβεβαιωμένες ηχογραφήσεις με την αιτία τους. Πάτησε ▶ για να τις ακούσεις ξανά."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        // ---- Controls (moved here from Settings) ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                tr("Αποθήκευση ηχογραφήσεων"),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                tr("Κρατά τοπικά κάθε κλάμα μαζί με την αιτία, για να χτίσεις δικό σου dataset."),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        Switch(checked = saveClips, onCheckedChange = viewModel::setSaveClips)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        dataset?.let { (n, bytes) -> datasetInfoText(n, bytes) } ?: tr("Υπολογισμός..."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Divider(Modifier.padding(vertical = 8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onExportDataset)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Archive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr("Εξαγωγή dataset (zip)"), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                tr("Εξάγει τις επιβεβαιωμένες ηχογραφήσεις + labels.csv για εκπαίδευση. Μένουν στη συσκευή σου."),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }

        if (clips.isEmpty()) {
            item {
                Text(
                    tr("Δεν υπάρχουν ακόμη αποθηκευμένες ηχογραφήσεις. Μόλις επιβεβαιώσεις γιατί έκλαψε το μωρό, η ηχογράφηση αποθηκεύεται εδώ (εφόσον είναι ενεργή η «Αποθήκευση ηχογραφήσεων» πιο πάνω)."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            items(clips, key = { it.id }) { e ->
                LibraryRow(
                    event = e,
                    labels = labels,
                    time = fmt.format(Date(e.timestamp)),
                    onPlay = { viewModel.playStoredClip(e.id) },
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun datasetInfoText(n: Int, bytes: Long): String = when (currentAppLang) {
    AppLang.EN -> "Stored: $n recordings • ${formatBytes(bytes)}"
    AppLang.EL -> "Αποθηκευμένα: $n ηχογραφήσεις • ${formatBytes(bytes)}"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun LibraryRow(
    event: CryEvent,
    labels: List<CryReason>,
    time: String,
    onPlay: () -> Unit,
) {
    val reason = event.confirmedIndex?.takeIf { it in labels.indices }?.let { labels[it] }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(reason?.emoji ?: "🍼", fontSize = 30.sp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    reason?.let { tr(it.displayName) } ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    time,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            FilledTonalIconButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = tr("Άκου ξανά"))
            }
        }
    }
}
