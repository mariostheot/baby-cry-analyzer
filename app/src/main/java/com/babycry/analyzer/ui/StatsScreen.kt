package com.babycry.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.data.StatsSummary
import com.babycry.analyzer.data.CryEvent
import com.babycry.analyzer.data.DiaperEvent
import com.babycry.analyzer.data.FeedingEvent
import com.babycry.analyzer.data.TummyTimeEvent
import com.babycry.analyzer.data.WeightEvent
import com.babycry.analyzer.data.HeightEvent
import com.babycry.analyzer.model.DiaperType
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.tr
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun StatsScreen(viewModel: CryViewModel, modifier: Modifier = Modifier) {
    var stats by remember { mutableStateOf<StatsSummary?>(null) }
    val language by viewModel.language.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val feedbackCount by viewModel.feedbackCount.collectAsState()
    val events by viewModel.recentEvents.collectAsState()
    val feedings by viewModel.recentFeedings.collectAsState()
    val diapers by viewModel.recentDiapers.collectAsState()
    val tummy by viewModel.recentTummy.collectAsState()
    val weights by viewModel.recentWeights.collectAsState()
    val heights by viewModel.recentHeights.collectAsState()
    val careInsights by viewModel.careInsights.collectAsState()
    var showWeightAdd by remember { mutableStateOf(false) }
    var editWeight by remember { mutableStateOf<WeightEvent?>(null) }
    var showHeightAdd by remember { mutableStateOf(false) }
    var editHeight by remember { mutableStateOf<HeightEvent?>(null) }

    LaunchedEffect(language, profile.id, events, feedbackCount) { stats = viewModel.loadStats() }
    LaunchedEffect(profile.id) {
        // A measurement belongs to one baby. Do not leave an edit dialog open across a switch.
        showWeightAdd = false
        editWeight = null
        showHeightAdd = false
        editHeight = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            tr("Πώς τα πάει"),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Text(
            tr("Τα νούμερα βασίζονται στις δικές σου επιβεβαιώσεις και διορθώσεις. Όσο περισσότερο απαντάς, τόσο πιο ακριβή γίνονται."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(12.dp))

        val s = stats
        if (s == null) {
            Text(tr("Φόρτωση..."))
            return@Column
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                MetricRow(tr("Τρόπος ανάλυσης"), tr(if (s.hasModel) "Έξυπνο μοντέλο (AI)" else "Απλή εκτίμηση"))
                MetricRow(tr("Κλάματα που καταγράφηκαν"), s.totalCries.toString())
                MetricRow(tr("Φορές που έδωσες απάντηση"), s.confirmedCount.toString())
                MetricRow(
                    tr("Σωστές προβλέψεις"),
                    s.accuracy?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                )
                MetricRow(tr("Όσα έμαθε από εσένα"), s.feedbackCount.toString())
                MetricRow(
                    tr("Προσαρμογή στο μωρό σου"),
                    tr(
                        when {
                            !s.tier2Available -> "δεν υποστηρίζεται εδώ"
                            s.tier2Ready -> "ενεργή"
                            else -> "μαθαίνει ακόμη"
                        },
                    ),
                )
                if (s.validationCount > 0) {
                    val baseAccuracy = s.validationBaseAccuracy
                        ?.let { "${(it * 100).roundToInt()}%" } ?: "—"
                    val personalizedAccuracy = s.validationPersonalizedAccuracy
                        ?.let { "${(it * 100).roundToInt()}%" } ?: "—"
                    MetricRow(tr("Σταθερό προσωπικό τεστ"), s.validationCount.toString())
                    MetricRow(
                        tr("Χωρίς / με προσαρμογή"),
                        "$baseAccuracy / $personalizedAccuracy",
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Caption(
            tr(
                "• «Φορές που έδωσες απάντηση»: πόσες φορές πάτησες ✓ ή διόρθωσες μια πρόβλεψη.\n" +
                    "• «Σωστές προβλέψεις»: από αυτές, πόσες τις είχε βρει σωστά η εφαρμογή.\n" +
                    "• «Προσαρμογή στο μωρό σου»: όταν μαζευτούν αρκετές διορθώσεις, το μοντέλο προσαρμόζεται ειδικά στο δικό σου μωρό.\n" +
                    "• «Σταθερό προσωπικό τεστ»: οι πρώτες 3 επιβεβαιώσεις ανά αιτία δεν χρησιμοποιούνται για εκπαίδευση, ώστε η σύγκριση να είναι δίκαιη.",
            ),
        )

        Spacer(Modifier.height(18.dp))
        BabyDayTimelineCard(
            cries = events.filter { it.cryDetected },
            feedings = feedings,
            diapers = diapers,
            tummy = tummy,
        )

        Spacer(Modifier.height(18.dp))
        WeightCard(
            weights = weights,
            onAdd = { showWeightAdd = true },
            onEdit = { editWeight = it },
        )

        Spacer(Modifier.height(18.dp))
        HeightCard(
            heights = heights,
            onAdd = { showHeightAdd = true },
            onEdit = { editHeight = it },
        )

        Spacer(Modifier.height(18.dp))
        CareInsightsStatsSection(state = careInsights)

        Spacer(Modifier.height(18.dp))
        SectionHeader(
            tr("Πόσο συχνά εμφανίζεται κάθε αιτία"),
            tr("Η κατανομή όλων των κλαμάτων που κατέγραψες."),
        )
        if (s.totalCries == 0) {
            Caption(tr("Δεν υπάρχουν ακόμη καταγραφές."))
        } else {
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val maxCount = (s.predictedDistribution.maxOrNull() ?: 0).coerceAtLeast(1)
                    s.labels.forEachIndexed { i, reason ->
                        val count = s.predictedDistribution.getOrElse(i) { 0 }
                        val pct = 100 * count / s.totalCries.coerceAtLeast(1)
                        DistributionBar(
                            label = "${reason.emoji} ${tr(reason.displayName)}",
                            value = "$count ($pct%)",
                            fraction = count.toFloat() / maxCount,
                        )
                        if (i < s.labels.lastIndex) Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionHeader(
            tr("Πόσο καλά αναγνωρίζει κάθε αιτία"),
            tr(
                "Από τις φορές που η αιτία ήταν πραγματικά αυτή (με βάση τις διορθώσεις σου), " +
                    "πόσες τις βρήκε σωστά. Υψηλότερο = καλύτερα.",
            ),
        )
        if (s.confirmedCount == 0) {
            Caption(tr("Δώσε μερικές απαντήσεις/διορθώσεις για να εμφανιστούν αυτά τα ποσοστά."))
        } else {
            Spacer(Modifier.height(8.dp))
            s.labels.forEachIndexed { i, reason ->
                MetricRow(
                    "${reason.emoji} ${tr(reason.displayName)}",
                    s.perClassRecall.getOrNull(i)?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Caption(tr("Διαχείριση/μηδενισμός δεδομένων: στις Ρυθμίσεις (μενού ⋮ πάνω δεξιά)."))
    }

    if (showWeightAdd) {
        WeightEntryDialog(
            initial = null,
            onDismiss = { showWeightAdd = false },
            onSave = { grams, timestamp ->
                viewModel.addWeight(grams, timestamp)
                showWeightAdd = false
            },
            onDelete = null,
        )
    }
    editWeight?.let { event ->
        WeightEntryDialog(
            initial = event,
            onDismiss = { editWeight = null },
            onSave = { grams, timestamp ->
                viewModel.updateWeight(event.id, grams, timestamp)
                editWeight = null
            },
            onDelete = {
                viewModel.deleteWeight(event.id)
                editWeight = null
            },
        )
    }
    if (showHeightAdd) {
        HeightEntryDialog(
            initial = null,
            onDismiss = { showHeightAdd = false },
            onSave = { millimeters, timestamp ->
                viewModel.addHeight(millimeters, timestamp)
                showHeightAdd = false
            },
            onDelete = null,
        )
    }
    editHeight?.let { event ->
        HeightEntryDialog(
            initial = event,
            onDismiss = { editHeight = null },
            onSave = { millimeters, timestamp ->
                viewModel.updateHeight(event.id, millimeters, timestamp)
                editHeight = null
            },
            onDelete = {
                viewModel.deleteHeight(event.id)
                editHeight = null
            },
        )
    }
}

@Composable
private fun BabyDayTimelineCard(
    cries: List<CryEvent>,
    feedings: List<FeedingEvent>,
    diapers: List<DiaperEvent>,
    tummy: List<TummyTimeEvent>,
) {
    SectionHeader(
        tr("Ημέρα του μωρού"),
        tr("Μια γρήγορη γραμμή με τα σημερινά κλάματα, ταΐσματα, πάνες και tummy time."),
    )
    Spacer(Modifier.height(8.dp))
    val todayStart = startOfToday()
    val items = buildList {
        cries.filter { it.timestamp >= todayStart }.forEach { add(TimelineDot(it.timestamp, "😢", tr("Κλάμα"))) }
        feedings.filter { it.timestamp >= todayStart && it.durationMs >= 0L }.forEach { add(TimelineDot(it.timestamp, "🍼", tr("Τάισμα"))) }
        diapers.filter { it.timestamp >= todayStart }.forEach {
            val type = DiaperType.fromNameOrNull(it.type) ?: DiaperType.WET
            add(TimelineDot(it.timestamp, type.emoji, tr("Πάνα")))
        }
        tummy.filter { it.timestamp >= todayStart }.forEach { add(TimelineDot(it.timestamp, "🤸", tr("Tummy Time"))) }
    }.sortedBy { it.timestamp }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (items.isEmpty()) {
                Caption(tr("Δεν υπάρχουν ακόμη σημερινές καταγραφές."))
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.takeLast(10).forEach { dot ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(dot.emoji, style = MaterialTheme.typography.titleMedium)
                            Text(
                                hourMinute(dot.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                dot.label,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Caption(tr("Δείχνει τις τελευταίες σημερινές καταγραφές με σειρά ώρας."))
            }
        }
    }
}

@Composable
private fun WeightCard(
    weights: List<WeightEvent>,
    onAdd: () -> Unit,
    onEdit: (WeightEvent) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("Βάρος"), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onAdd) {
                    Text("＋ " + tr("Προσθήκη βάρους"))
                }
            }
            if (weights.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Caption(
                    tr(
                        "Δεν έχει καταγραφεί βάρος ακόμη. Πρόσθεσε το βάρος από τις επισκέψεις στον παιδίατρο.",
                    ),
                )
            } else {
                WeightLineChart(points = weights, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                val latest = weights.first()
                Text(
                    tr("Τρέχον") + ": " + formatWeightKg(latest.grams),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                val listFmt = remember(currentAppLang) {
                    SimpleDateFormat(
                        "d/M/yyyy",
                        if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el"),
                    )
                }
                weights.forEach { w ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(w) }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(listFmt.format(Date(w.timestamp)), style = MaterialTheme.typography.bodyMedium)
                        Text(formatWeightKg(w.grams), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeightLineChart(
    points: List<WeightEvent>,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(points) { points.sortedBy { it.timestamp } }
    if (sorted.isEmpty()) return

    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    val dayFmt = remember(locale) { SimpleDateFormat("d/M", locale) }
    val primary = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    val minTs = sorted.first().timestamp.toFloat()
    val maxTs = sorted.last().timestamp.toFloat()
    val minG = sorted.minOf { it.grams }.toFloat()
    val maxG = sorted.maxOf { it.grams }.toFloat()
    val gPad = ((maxG - minG) * 0.08f).coerceAtLeast(50f)
    val yMin = minG - gPad
    val yMax = maxG + gPad
    val tsRange = (maxTs - minTs).coerceAtLeast(1f)

    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            Text(
                formatWeightKg(sorted.maxOf { it.grams }),
                style = labelStyle,
                color = labelColor,
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                formatWeightKg(sorted.minOf { it.grams }),
                style = labelStyle,
                color = labelColor,
                modifier = Modifier.align(Alignment.BottomStart),
            )
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 48.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
            ) {
                val w = size.width
                val h = size.height
                fun xFor(ts: Long): Float =
                    if (sorted.size == 1) w / 2f else ((ts - minTs) / tsRange) * w
                fun yFor(g: Int): Float =
                    h - ((g - yMin) / (yMax - yMin).coerceAtLeast(1f)) * h

                if (sorted.size == 1) {
                    drawCircle(
                        color = primary,
                        radius = 6.dp.toPx(),
                        center = Offset(w / 2f, h / 2f),
                    )
                } else {
                    for (i in 0 until sorted.lastIndex) {
                        val a = sorted[i]
                        val b = sorted[i + 1]
                        drawLine(
                            color = primary,
                            start = Offset(xFor(a.timestamp), yFor(a.grams)),
                            end = Offset(xFor(b.timestamp), yFor(b.grams)),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                    sorted.forEach { e ->
                        drawCircle(
                            color = primary,
                            radius = 4.dp.toPx(),
                            center = Offset(xFor(e.timestamp), yFor(e.grams)),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dayFmt.format(Date(sorted.first().timestamp)), style = labelStyle, color = labelColor)
            Text(dayFmt.format(Date(sorted.last().timestamp)), style = labelStyle, color = labelColor)
        }
    }
}

@Composable
private fun WeightEntryDialog(
    initial: WeightEvent?,
    onDismiss: () -> Unit,
    onSave: (grams: Int, timestamp: Long) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var kgText by remember(initial?.id) {
        mutableStateOf(
            initial?.grams?.let { g ->
                val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
                String.format(locale, "%.2f", g / 1000.0)
            } ?: "",
        )
    }
    var timestampMillis by remember(initial?.id) {
        mutableStateOf(initial?.timestamp ?: System.currentTimeMillis())
    }
    var showDatePicker by remember { mutableStateOf(false) }
    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    val dateFmt = remember(locale) { SimpleDateFormat("dd/MM/yyyy", locale) }
    val parsedKg = kgText.trim()
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { value -> kotlin.math.round(value * 1000).toInt() in 1..14_999 }
    val timestampValid = timestampMillis <= System.currentTimeMillis()
    val valid = parsedKg != null && timestampValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (initial == null) "Προσθήκη βάρους" else "Επεξεργασία βάρους")) },
        text = {
            Column {
                OutlinedTextField(
                    value = kgText,
                    onValueChange = { kgText = it },
                    label = { Text(tr("Βάρος (kg)")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(dateFmt.format(Date(timestampMillis)))
                }
                if (!timestampValid) {
                    Text(
                        tr("Διάλεξε σημερινή ή παλαιότερη ημερομηνία."),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val kg = parsedKg ?: return@TextButton
                    onSave(kotlin.math.round(kg * 1000).toInt(), timestampMillis)
                },
                enabled = valid,
            ) { Text(tr("Αποθήκευση")) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(tr("Διαγραφή")) }
                }
                TextButton(onClick = onDismiss) { Text(tr("Άκυρο")) }
            }
        },
    )

    if (showDatePicker) {
        DmyDateInputDialog(
            initialDateMillis = timestampMillis,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                timestampMillis = it
                showDatePicker = false
            },
            title = tr("Ημερομηνία μέτρησης"),
        )
    }
}

@Composable
private fun formatWeightKg(grams: Int): String {
    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    return String.format(locale, "%.2f kg", grams / 1000.0)
}

@Composable
private fun HeightCard(
    heights: List<HeightEvent>,
    onAdd: () -> Unit,
    onEdit: (HeightEvent) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("Ύψος"), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onAdd) {
                    Text("＋ " + tr("Προσθήκη ύψους"))
                }
            }
            if (heights.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Caption(
                    tr(
                        "Δεν έχει καταγραφεί ύψος ακόμη. Πρόσθεσε το ύψος από τις επισκέψεις στον παιδίατρο.",
                    ),
                )
            } else {
                HeightLineChart(points = heights, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                val latest = heights.first()
                Text(
                    tr("Τρέχον ύψος") + ": " + formatHeightCm(latest.millimeters),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                val listFmt = remember(currentAppLang) {
                    SimpleDateFormat(
                        "d/M/yyyy",
                        if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el"),
                    )
                }
                heights.forEach { h ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(h) }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(listFmt.format(Date(h.timestamp)), style = MaterialTheme.typography.bodyMedium)
                        Text(formatHeightCm(h.millimeters), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeightLineChart(
    points: List<HeightEvent>,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(points) { points.sortedBy { it.timestamp } }
    if (sorted.isEmpty()) return

    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    val dayFmt = remember(locale) { SimpleDateFormat("d/M", locale) }
    val primary = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    val minTs = sorted.first().timestamp.toFloat()
    val maxTs = sorted.last().timestamp.toFloat()
    val minMm = sorted.minOf { it.millimeters }.toFloat()
    val maxMm = sorted.maxOf { it.millimeters }.toFloat()
    val mmPad = ((maxMm - minMm) * 0.08f).coerceAtLeast(5f)
    val yMin = minMm - mmPad
    val yMax = maxMm + mmPad
    val tsRange = (maxTs - minTs).coerceAtLeast(1f)

    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            Text(
                formatHeightCm(sorted.maxOf { it.millimeters }),
                style = labelStyle,
                color = labelColor,
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                formatHeightCm(sorted.minOf { it.millimeters }),
                style = labelStyle,
                color = labelColor,
                modifier = Modifier.align(Alignment.BottomStart),
            )
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 48.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
            ) {
                val w = size.width
                val h = size.height
                fun xFor(ts: Long): Float =
                    if (sorted.size == 1) w / 2f else ((ts - minTs) / tsRange) * w
                fun yFor(mm: Int): Float =
                    h - ((mm - yMin) / (yMax - yMin).coerceAtLeast(1f)) * h

                if (sorted.size == 1) {
                    drawCircle(
                        color = primary,
                        radius = 6.dp.toPx(),
                        center = Offset(w / 2f, h / 2f),
                    )
                } else {
                    for (i in 0 until sorted.lastIndex) {
                        val a = sorted[i]
                        val b = sorted[i + 1]
                        drawLine(
                            color = primary,
                            start = Offset(xFor(a.timestamp), yFor(a.millimeters)),
                            end = Offset(xFor(b.timestamp), yFor(b.millimeters)),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                    sorted.forEach { e ->
                        drawCircle(
                            color = primary,
                            radius = 4.dp.toPx(),
                            center = Offset(xFor(e.timestamp), yFor(e.millimeters)),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dayFmt.format(Date(sorted.first().timestamp)), style = labelStyle, color = labelColor)
            Text(dayFmt.format(Date(sorted.last().timestamp)), style = labelStyle, color = labelColor)
        }
    }
}

@Composable
private fun HeightEntryDialog(
    initial: HeightEvent?,
    onDismiss: () -> Unit,
    onSave: (millimeters: Int, timestamp: Long) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var cmText by remember(initial?.id) {
        mutableStateOf(
            initial?.millimeters?.let { mm ->
                val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
                String.format(locale, "%.1f", mm / 10.0)
            } ?: "",
        )
    }
    var timestampMillis by remember(initial?.id) {
        mutableStateOf(initial?.timestamp ?: System.currentTimeMillis())
    }
    var showDatePicker by remember { mutableStateOf(false) }
    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    val dateFmt = remember(locale) { SimpleDateFormat("dd/MM/yyyy", locale) }
    val parsedCm = cmText.trim()
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { value -> kotlin.math.round(value * 10).toInt() in 1..1499 }
    val timestampValid = timestampMillis <= System.currentTimeMillis()
    val valid = parsedCm != null && timestampValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (initial == null) "Προσθήκη ύψους" else "Επεξεργασία ύψους")) },
        text = {
            Column {
                OutlinedTextField(
                    value = cmText,
                    onValueChange = { cmText = it },
                    label = { Text(tr("Ύψος (cm)")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(dateFmt.format(Date(timestampMillis)))
                }
                if (!timestampValid) {
                    Text(
                        tr("Διάλεξε σημερινή ή παλαιότερη ημερομηνία."),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cm = parsedCm ?: return@TextButton
                    onSave(kotlin.math.round(cm * 10).toInt(), timestampMillis)
                },
                enabled = valid,
            ) { Text(tr("Αποθήκευση")) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(tr("Διαγραφή")) }
                }
                TextButton(onClick = onDismiss) { Text(tr("Άκυρο")) }
            }
        },
    )

    if (showDatePicker) {
        DmyDateInputDialog(
            initialDateMillis = timestampMillis,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                timestampMillis = it
                showDatePicker = false
            },
            title = tr("Ημερομηνία μέτρησης"),
        )
    }
}

@Composable
private fun formatHeightCm(millimeters: Int): String {
    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    return String.format(locale, "%.1f cm", millimeters / 10.0)
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(2.dp))
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}

private data class TimelineDot(val timestamp: Long, val emoji: String, val label: String)

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun hourMinute(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
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
