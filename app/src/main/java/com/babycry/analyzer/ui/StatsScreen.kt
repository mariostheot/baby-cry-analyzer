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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.data.StatsSummary
import kotlin.math.roundToInt

@Composable
fun StatsScreen(viewModel: CryViewModel, modifier: Modifier = Modifier) {
    var stats by remember { mutableStateOf<StatsSummary?>(null) }

    LaunchedEffect(Unit) { stats = viewModel.loadStats() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "Στατιστικά & ακρίβεια",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        val s = stats
        if (s == null) {
            Text("Φόρτωση...")
            return@Column
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                MetricRow("Μηχανή", if (s.hasModel) "AI μοντέλο" else "Πρόχειρη εκτίμηση")
                MetricRow("Καταγεγραμμένα κλάματα", s.totalCries.toString())
                MetricRow("Επιβεβαιωμένες κρίσεις", s.confirmedCount.toString())
                MetricRow(
                    "Ακρίβεια (από feedback)",
                    s.accuracy?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                )
                MetricRow("Δείγματα εκμάθησης", s.feedbackCount.toString())
                MetricRow(
                    "Προσωποποίηση Tier 2",
                    when {
                        !s.tier2Available -> "μη διαθέσιμη"
                        s.tier2Ready -> "ενεργή"
                        else -> "σε αναμονή δεδομένων"
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Κατανομή αιτιών", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        if (s.totalCries == 0) {
            Text(
                "Δεν υπάρχουν ακόμη καταγραφές.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val maxCount = (s.predictedDistribution.maxOrNull() ?: 0).coerceAtLeast(1)
                    s.labels.forEachIndexed { i, reason ->
                        val count = s.predictedDistribution.getOrElse(i) { 0 }
                        val pct = 100 * count / s.totalCries.coerceAtLeast(1)
                        DistributionBar(
                            label = "${reason.emoji} ${reason.displayName}",
                            value = "$count ($pct%)",
                            fraction = count.toFloat() / maxCount,
                        )
                        if (i < s.labels.lastIndex) Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Ανάκληση ανά κατηγορία (recall)", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        s.labels.forEachIndexed { i, reason ->
            MetricRow(
                "${reason.emoji} ${reason.displayName}",
                s.perClassRecall.getOrNull(i)?.let { "${(it * 100).roundToInt()}%" } ?: "—",
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("Πίνακας σύγχυσης (γραμμή=σωστό)", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        ConfusionMatrix(s)

        Spacer(Modifier.height(12.dp))
        Text(
            "Οι διορθώσεις σου τροφοδοτούν αυτά τα νούμερα. Διαχείριση δεδομένων στις Ρυθμίσεις.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun DistributionBar(label: String, value: String, fraction: Float) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ConfusionMatrix(s: StatsSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1.2f)) { Text("", style = MaterialTheme.typography.bodyMedium) }
                s.labels.forEach { r ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(r.emoji, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            s.labels.forEachIndexed { i, rowReason ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1.2f)) {
                        Text(rowReason.emoji, style = MaterialTheme.typography.bodyMedium)
                    }
                    for (j in s.labels.indices) {
                        val count = s.confusion.getOrNull(i)?.getOrNull(j) ?: 0
                        Box(
                            Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                count.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                fontWeight = if (i == j) FontWeight.Bold else FontWeight.Normal,
                                color = if (i == j) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
