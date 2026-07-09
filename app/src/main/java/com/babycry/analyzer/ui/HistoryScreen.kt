package com.babycry.analyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.data.CryEvent
import com.babycry.analyzer.model.CryReason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val dateFormat = SimpleDateFormat("EEE dd/MM HH:mm", Locale("el"))

@Composable
fun HistoryScreen(viewModel: CryViewModel, modifier: Modifier = Modifier) {
    val events by viewModel.recentEvents.collectAsState()
    val feedings by viewModel.recentFeedings.collectAsState()
    val labels = viewModel.labels

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Ιστορικό",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        if (events.isEmpty()) {
            item { Text("Δεν υπάρχουν ακόμα καταγραφές. Πάτα «Άκου το μωρό».") }
        } else {
            item { HourPatternCard(events) }
        }

        items(events, key = { it.id }) { event ->
            EventRow(event, labels)
        }

        if (feedings.isNotEmpty()) {
            item {
                Text(
                    "Ταΐσματα",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(feedings, key = { "feed_${it.id}" }) { feeding ->
                Text(
                    "🍼  ${dateFormat.format(Date(feeding.timestamp))}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun EventRow(event: CryEvent, labels: List<CryReason>) {
    val predicted = event.predictedIndex.takeIf { it in labels.indices }?.let { labels[it] }
    val confirmed = event.confirmedIndex?.takeIf { it in labels.indices }?.let { labels[it] }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        !event.cryDetected -> "🔇 Χωρίς κλάμα"
                        predicted != null -> "${predicted.emoji} ${predicted.displayName}"
                        else -> "—"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    dateFormat.format(Date(event.timestamp)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                if (confirmed != null) {
                    val ok = confirmed.ordinal == event.predictedIndex
                    Text(
                        text = if (ok) "✓ επιβεβαιώθηκε"
                        else "✎ διόρθωση: ${confirmed.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ok) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (event.cryDetected) {
                Text(
                    "${(event.confidence * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** A tiny bar chart of cries per hour-of-day, to spot patterns. */
@Composable
private fun HourPatternCard(events: List<CryEvent>) {
    val counts = IntArray(24)
    events.filter { it.cryDetected }.forEach {
        val hour = ((it.timestamp / 3_600_000L) % 24L).toInt()
        counts[hour]++
    }
    if (counts.all { it == 0 }) return
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Κλάματα ανά ώρα", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                for (h in 0 until 24) {
                    val frac = counts[h].toFloat() / max
                    Box(
                        Modifier
                            .weight(1f)
                            .height((4 + frac * 50).dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(
                                    alpha = 0.3f + 0.7f * frac,
                                )
                            )
                    )
                }
            }
        }
    }
}
