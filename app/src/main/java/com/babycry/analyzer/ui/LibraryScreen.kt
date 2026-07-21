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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.babycry.analyzer.ui.i18n.translate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A dedicated "database" page for the parent's own recordings: every confirmed cry that still
 * has a saved clip. To stay readable as clips pile up, the list shows one day at a time (like
 * History) and steps only between days that actually have recordings.
 */
@Composable
fun LibraryScreen(viewModel: CryViewModel, modifier: Modifier = Modifier) {
    val labels = viewModel.labels
    val recents by viewModel.recentEvents.collectAsState()
    val language by viewModel.language.collectAsState()
    val playback by viewModel.playback.collectAsState()
    var clips by remember { mutableStateOf<List<CryEvent>>(emptyList()) }
    // Reload whenever events change (new confirmation, deletion) or the language flips.
    LaunchedEffect(recents, language) { clips = viewModel.libraryEvents() }

    val timeFmt = remember(language) {
        SimpleDateFormat("HH:mm", if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el"))
    }
    val dayHeaderFmt = remember(language) {
        SimpleDateFormat("EEEE d/M", if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el"))
    }

    val todayStart = remember { startOfDay(System.currentTimeMillis()) }
    // Newest recording day first, so the parent lands on the most recent clips.
    val recordingDays = remember(clips) {
        clips.map { startOfDay(it.timestamp) }.distinct().sortedDescending()
    }
    var selectedDayStart by remember { mutableLongStateOf(Long.MIN_VALUE) }
    LaunchedEffect(recordingDays) {
        if (selectedDayStart == Long.MIN_VALUE || recordingDays.none { it == selectedDayStart }) {
            selectedDayStart = recordingDays.firstOrNull() ?: Long.MIN_VALUE
        }
    }
    // Fall back to the newest recording day until the selection is initialized, so the
    // navigator never flashes an out-of-range placeholder day.
    val activeDayStart = if (recordingDays.contains(selectedDayStart)) {
        selectedDayStart
    } else {
        recordingDays.firstOrNull() ?: todayStart
    }
    val activeIndex = recordingDays.indexOf(activeDayStart)
    val dayClips = remember(clips, activeDayStart) {
        clips.filter { startOfDay(it.timestamp) == activeDayStart }
            .sortedByDescending { it.timestamp }
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
                    tr("Δεν υπάρχουν ακόμη αποθηκευμένες ηχογραφήσεις. Μόλις επιβεβαιώσεις γιατί έκλαψε το μωρό, η ηχογράφηση αποθηκεύεται εδώ αυτόματα."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            item {
                LibraryDayNavigationBar(
                    dayLabel = dayLabelFor(activeDayStart, todayStart, dayHeaderFmt),
                    dateLabel = dayHeaderFmt.format(Date(activeDayStart)),
                    count = dayClips.size,
                    // recordingDays is newest-first: a larger index is an older day.
                    hasOlder = activeIndex in 0 until recordingDays.lastIndex,
                    hasNewer = activeIndex > 0,
                    onOlder = {
                        if (activeIndex in 0 until recordingDays.lastIndex) {
                            selectedDayStart = recordingDays[activeIndex + 1]
                        }
                    },
                    onNewer = {
                        if (activeIndex > 0) selectedDayStart = recordingDays[activeIndex - 1]
                    },
                )
            }

            if (dayClips.isEmpty()) {
                item {
                    Text(
                        tr("Δεν υπάρχουν ηχογραφήσεις αυτή την ημέρα."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(dayClips, key = { it.id }) { e ->
                    LibraryRow(
                        event = e,
                        labels = labels,
                        time = timeFmt.format(Date(e.timestamp)),
                        onPlay = { viewModel.playStoredClip(e.id) },
                        isPlaying = playback.key == "event:${e.id}" && !playback.paused,
                        isPaused = playback.key == "event:${e.id}" && playback.paused,
                        onPause = { viewModel.pauseReplay() },
                        onResume = { viewModel.resumeReplay() },
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun LibraryDayNavigationBar(
    dayLabel: String,
    dateLabel: String,
    count: Int,
    hasOlder: Boolean,
    hasNewer: Boolean,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onOlder, enabled = hasOlder) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = tr("Προηγούμενη ημέρα"),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(dayLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "$dateLabel · $count",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        IconButton(onClick = onNewer, enabled = hasNewer) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = tr("Επόμενη ημέρα"),
            )
        }
    }
}

@Composable
private fun LibraryRow(
    event: CryEvent,
    labels: List<CryReason>,
    time: String,
    onPlay: () -> Unit,
    isPlaying: Boolean,
    isPaused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
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
            val action = when {
                isPlaying -> onPause
                isPaused -> onResume
                else -> onPlay
            }
            FilledTonalIconButton(onClick = action) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = tr(
                        when {
                            isPlaying -> "Παύση"
                            isPaused -> "Συνέχεια"
                            else -> "Άκου ξανά"
                        },
                    ),
                )
            }
        }
    }
}

private fun dayLabelFor(dayStart: Long, todayStart: Long, headerFmt: SimpleDateFormat): String = when (dayStart) {
    todayStart -> translate(currentAppLang, "Σήμερα")
    previousDayStart(todayStart) -> translate(currentAppLang, "Χθες")
    else -> headerFmt.format(Date(dayStart)).replaceFirstChar { it.uppercase() }
}

private fun previousDayStart(dayStart: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = dayStart
        add(Calendar.DATE, -1)
    }.timeInMillis

private fun startOfDay(ts: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
