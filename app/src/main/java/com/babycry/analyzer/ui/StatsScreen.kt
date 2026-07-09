package com.babycry.analyzer.ui

import android.content.Intent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.data.StatsSummary
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun StatsScreen(viewModel: CryViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<StatsSummary?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) { stats = viewModel.loadStats() }

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

        Spacer(Modifier.height(12.dp))
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

        Spacer(Modifier.height(20.dp))
        Divider()
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    val csv = viewModel.exportCsv()
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, "Baby cry history")
                        putExtra(Intent.EXTRA_TEXT, csv)
                    }
                    ctx.startActivity(Intent.createChooser(intent, "Εξαγωγή CSV"))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Εξαγωγή ιστορικού (CSV)")
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                viewModel.resetPersonalization()
                refresh++
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Μηδενισμός προσωποποίησης")
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) {
            Text("Ανανέωση")
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
            // Header
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
