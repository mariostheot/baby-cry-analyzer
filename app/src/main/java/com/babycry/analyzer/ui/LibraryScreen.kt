package com.babycry.analyzer.ui

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
 * A simple, tidy "database" of the parent's own recordings: every confirmed cry that still
 * has a saved clip, showing the confirmed reason + when it happened, with a play button to
 * hear it again. Kept fully on-device.
 */
@Composable
fun LibraryScreen(viewModel: CryViewModel, modifier: Modifier = Modifier) {
    val labels = viewModel.labels
    val recents by viewModel.recentEvents.collectAsState()
    val language by viewModel.language.collectAsState()
    var clips by remember { mutableStateOf<List<CryEvent>>(emptyList()) }
    // Reload whenever events change (new confirmation, deletion) or the language flips.
    LaunchedEffect(recents, language) { clips = viewModel.libraryEvents() }

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

        if (clips.isEmpty()) {
            item {
                Text(
                    tr("Δεν υπάρχουν ακόμη αποθηκευμένες ηχογραφήσεις. Μόλις επιβεβαιώσεις γιατί έκλαψε το μωρό, η ηχογράφηση αποθηκεύεται εδώ (εφόσον είναι ενεργή η «Αποθήκευση ηχογραφήσεων» στις Ρυθμίσεις)."),
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
