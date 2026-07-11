package com.babycry.analyzer.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.context.ContextPrior
import com.babycry.analyzer.data.CryEvent
import com.babycry.analyzer.data.FeedingEvent
import com.babycry.analyzer.model.BabyProfile
import com.babycry.analyzer.model.CryReason
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.tr
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun displayLocale(): Locale =
    if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")

private fun timeFormat() = SimpleDateFormat("HH:mm", displayLocale())
private fun dayHeaderFormat() = SimpleDateFormat("EEEE d/M", displayLocale())
private fun dayShortFormat() = SimpleDateFormat("EEE", displayLocale())

@Composable
fun HistoryScreen(viewModel: CryViewModel, modifier: Modifier = Modifier) {
    val events by viewModel.recentEvents.collectAsState()
    val feedings by viewModel.recentFeedings.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val labels = viewModel.labels
    val language by viewModel.language.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var editEvent by remember { mutableStateOf<CryEvent?>(null) }
    var pendingDelete by remember { mutableStateOf<CryEvent?>(null) }

    val now = System.currentTimeMillis()
    val cries = remember(events) { events.filter { it.cryDetected } }
    val summary = remember(events, feedings, profile, language) {
        computeSummary(cries, feedings, labels, profile, now)
    }
    val lines = remember(events, feedings, language) { buildTimeline(cries, feedings, now) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tr("Ιστορικό"), style = MaterialTheme.typography.headlineMedium)
                if (cries.isNotEmpty() || feedings.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text(tr("Καθαρισμός"))
                    }
                }
            }
        }

        if (cries.isEmpty() && feedings.isEmpty()) {
            item {
                Text(tr("Δεν υπάρχουν ακόμα καταγραφές. Πάτα «Άκου το μωρό» ή κατέγραψε ένα τάισμα."))
            }
        } else {
            item { LiveTiles(summary) }
            item { TodayCard(summary) }
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(tr("Χρονολόγιο"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        tr("Πάτησε μια καταγραφή για να ορίσεις/διορθώσεις την αιτία."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            items(
                lines,
                key = {
                    when (it) {
                        is Line.Header -> "h_${it.label}"
                        is Line.Cry -> "c_${it.e.id}"
                        is Line.Feed -> "f_${it.e.id}"
                    }
                },
            ) { line ->
                when (line) {
                    is Line.Header -> Text(
                        line.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                    is Line.Cry -> CryRow(
                        event = line.e,
                        labels = labels,
                        onEdit = { editEvent = line.e },
                        onDelete = { pendingDelete = line.e },
                    )
                    is Line.Feed -> FeedRow(line.e)
                }
            }
            item { PatternsCard(summary) }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Καθαρισμός ιστορικού;")) },
            text = {
                Text(tr("Θα διαγραφούν όλα τα καταγεγραμμένα κλάματα, τα γραφήματα και τα ταΐσματα. Αυτό που έμαθε το μοντέλο από εσένα ΔΕΝ επηρεάζεται."))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text(tr("Διαγραφή")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(tr("Άκυρο")) }
            },
        )
    }

    editEvent?.let { ev ->
        val currentIdx = ev.confirmedIndex
        AlertDialog(
            onDismissRequest = { editEvent = null },
            title = { Text(tr("Ποια ήταν η αιτία;")) },
            text = {
                Column {
                    Text(
                        tr("Διάλεξε τι ήταν τελικά. Ενημερώνονται τα στατιστικά και μαθαίνει το μοντέλο."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    labels.forEachIndexed { i, reason ->
                        val selected = i == currentIdx
                        OutlinedButton(
                            onClick = {
                                viewModel.setReasonForEvent(ev.id, reason)
                                editEvent = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            Text(
                                (if (selected) "✓ " else "") + "${reason.emoji} ${tr(reason.displayName)}",
                                maxLines = 1,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { editEvent = null }) { Text(tr("Κλείσιμο")) }
            },
        )
    }

    pendingDelete?.let { ev ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr("Διαγραφή κλάματος;")) },
            text = {
                Text(tr("Θα διαγραφεί οριστικά αυτή η καταγραφή κλάματος (και η ηχογράφησή της). Δεν μπορεί να αναιρεθεί."))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEvent(ev.id)
                    pendingDelete = null
                }) { Text(tr("Διαγραφή")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(tr("Άκυρο")) }
            },
        )
    }
}

@Composable
private fun LiveTiles(s: HistorySummary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val overdue = s.nextFeedInMs != null && s.nextFeedInMs <= 0
        Tile(
            modifier = Modifier.weight(1f),
            emoji = "🍼",
            title = tr("Τελευταίο τάισμα"),
            big = s.lastFeedAgoMs?.let(::relativeAgo) ?: "—",
            subtitle = when {
                s.lastFeedAgoMs == null -> tr("δεν έχει καταγραφεί")
                s.nextFeedInMs == null -> ""
                overdue -> tr("ίσως πεινάει")
                else -> nextFeedSubtitle(s.nextFeedInMs)
            },
            subtitleAccent = overdue,
        )
        Tile(
            modifier = Modifier.weight(1f),
            emoji = "😢",
            title = tr("Τελευταίο κλάμα"),
            big = s.lastCryAgoMs?.let(::relativeAgo) ?: "—",
            subtitle = s.lastCryReason?.let { "${it.emoji} ${tr(it.displayName)}" } ?: "—",
            subtitleAccent = false,
        )
    }
}

private fun nextFeedSubtitle(ms: Long): String = when (currentAppLang) {
    AppLang.EN -> "next in ~${durationShort(ms)}"
    AppLang.EL -> "επόμενο σε ~${durationShort(ms)}"
}

@Composable
private fun Tile(
    modifier: Modifier,
    emoji: String,
    title: String,
    big: String,
    subtitle: String,
    subtitleAccent: Boolean,
) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "$emoji  $title",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(6.dp))
            Text(big, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (subtitleAccent) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun TodayCard(s: HistorySummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(tr("Σήμερα"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            InsightLine(tr("Κλάματα"), s.criesToday.toString())
            val diff = s.criesToday - s.criesYesterday
            val cmp = criesComparison(diff, s.criesYesterday)
            Text(
                cmp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(6.dp))
            InsightLine(tr("Ταΐσματα"), s.feedsToday.toString())
            if (s.topReasonToday != null) {
                Spacer(Modifier.height(6.dp))
                InsightLine(
                    tr("Πιο συχνή αιτία"),
                    "${s.topReasonToday.emoji} ${tr(s.topReasonToday.displayName)}",
                )
            }
        }
    }
}

private fun criesComparison(diff: Int, yesterday: Int): String = when (currentAppLang) {
    AppLang.EN -> when {
        diff < 0 -> "↓ fewer than yesterday (yesterday: $yesterday)"
        diff > 0 -> "↑ more than yesterday (yesterday: $yesterday)"
        else -> "≈ same as yesterday (yesterday: $yesterday)"
    }
    AppLang.EL -> when {
        diff < 0 -> "↓ λιγότερα από χθες (χθες: $yesterday)"
        diff > 0 -> "↑ περισσότερα από χθες (χθες: $yesterday)"
        else -> "≈ ίδια με χθες (χθες: $yesterday)"
    }
}

@Composable
private fun CryRow(
    event: CryEvent,
    labels: List<CryReason>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val predicted = event.predictedIndex.takeIf { it in labels.indices }?.let { labels[it] }
    val confirmed = event.confirmedIndex?.takeIf { it in labels.indices }?.let { labels[it] }
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = predicted?.let { "${it.emoji} ${tr(it.displayName)}" } ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    timeFormat().format(Date(event.timestamp)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                if (confirmed != null) {
                    val ok = confirmed.ordinal == event.predictedIndex
                    Text(
                        text = if (ok) tr("✓ επιβεβαιώθηκε")
                        else correctionLabel(confirmed),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(
                "${(event.confidence * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = tr("Διαγραφή"),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        }
    }
}

@Composable
private fun correctionLabel(confirmed: CryReason): String = when (currentAppLang) {
    AppLang.EN -> "✎ corrected: ${tr(confirmed.displayName)}"
    AppLang.EL -> "✎ διόρθωση: ${confirmed.displayName}"
}

@Composable
private fun FeedRow(feeding: FeedingEvent) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("🍼  ${tr("Τάισμα")}", style = MaterialTheme.typography.bodyLarge)
            Text(
                timeFormat().format(Date(feeding.timestamp)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun PatternsCard(s: HistorySummary) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(tr("Μοτίβα"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            InsightLine(
                tr("Ώρες αιχμής κλάματος"),
                s.peakHour?.let { "%02d:00–%02d:00".format(it, (it + 1) % 24) } ?: "—",
            )
            Spacer(Modifier.height(6.dp))
            InsightLine(
                tr("Μέσο διάστημα ταϊσμάτων"),
                s.avgFeedGapMs?.let { durationShort(it) } ?: "—",
            )

            Spacer(Modifier.height(14.dp))
            Text(tr("Κλάμα ανά ώρα της ημέρας"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                tr("Πιο σκούρο = περισσότερα κλάματα εκείνη την ώρα. Δείχνει τις «δύσκολες» ώρες (συχνά αργά το απόγευμα/βράδυ)."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            HourHeatmap(s.perHour)

            Spacer(Modifier.height(14.dp))
            Text(tr("Κλάματα ανά ημέρα (τελευταίες 7)"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            val maxDay = (s.perDay.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                s.perDay.forEach { (label, count) ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Text(
                            count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((6 + 46f * count / maxDay).dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A 24-cell strip showing which hour of the day the baby tends to cry in. Darker = more
 * cries in that hour (a compact "heatmap"), so parents can spot the classic evening
 * "witching hour" at a glance.
 */
@Composable
private fun HourHeatmap(perHour: List<Int>) {
    val max = (perHour.maxOrNull() ?: 0).coerceAtLeast(1)
    val peak = perHour.indexOf(perHour.maxOrNull() ?: 0)
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (h in 0 until 24) {
                val c = perHour.getOrElse(h) { 0 }
                Box(
                    Modifier
                        .weight(1f)
                        .height(26.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (c == 0) empty else base.copy(alpha = 0.25f + 0.75f * (c.toFloat() / max))),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "23").forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        if (perHour.sum() > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${tr("Ώρα αιχμής:")} %02d:00–%02d:00".format(peak, (peak + 1) % 24),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun InsightLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

// ---------------- data + helpers ----------------

private sealed interface Line {
    data class Header(val label: String) : Line
    data class Cry(val e: CryEvent) : Line
    data class Feed(val e: FeedingEvent) : Line
}

private data class HistorySummary(
    val lastFeedAgoMs: Long?,
    val nextFeedInMs: Long?,
    val lastCryAgoMs: Long?,
    val lastCryReason: CryReason?,
    val criesToday: Int,
    val criesYesterday: Int,
    val feedsToday: Int,
    val topReasonToday: CryReason?,
    val peakHour: Int?,
    val avgFeedGapMs: Long?,
    val perDay: List<Pair<String, Int>>,
    val perHour: List<Int>,     // 24 slots, cries started in each hour-of-day
)

private fun reasonOf(e: CryEvent, labels: List<CryReason>): CryReason? {
    val idx = e.confirmedIndex?.takeIf { it in labels.indices } ?: e.predictedIndex
    return idx.takeIf { it in labels.indices }?.let { labels[it] }
}

private fun computeSummary(
    cries: List<CryEvent>,
    feedings: List<FeedingEvent>,
    labels: List<CryReason>,
    profile: BabyProfile,
    now: Long,
): HistorySummary {
    val dayMs = 86_400_000L
    val todayStart = startOfDay(now)

    val lastFeed = feedings.firstOrNull()?.timestamp
    val expectedHours = ContextPrior.expectedFeedIntervalHours(profile.ageMonths(now), profile.ageDays(now))
    val nextFeedInMs = lastFeed?.let { it + (expectedHours * 3_600_000L).toLong() - now }

    val lastCry = cries.firstOrNull()

    val perHour = IntArray(24)
    val cal = Calendar.getInstance()
    for (e in cries) {
        cal.timeInMillis = e.timestamp
        perHour[cal.get(Calendar.HOUR_OF_DAY)]++
    }
    var peakHour: Int? = null
    for (h in 0 until 24) if (perHour[h] > 0 && (peakHour == null || perHour[h] > perHour[peakHour!!])) peakHour = h

    val todayCounts = IntArray(labels.size)
    for (e in cries) if (e.timestamp >= todayStart) {
        reasonOf(e, labels)?.let { todayCounts[it.ordinal]++ }
    }
    var topTodayIdx = -1
    for (i in todayCounts.indices) if (todayCounts[i] > 0 && (topTodayIdx < 0 || todayCounts[i] > todayCounts[topTodayIdx])) topTodayIdx = i
    val topReasonToday = topTodayIdx.takeIf { it >= 0 }?.let { labels[it] }

    val avgFeedGapMs = if (feedings.size >= 2) {
        var sum = 0L
        for (i in 0 until feedings.size - 1) sum += (feedings[i].timestamp - feedings[i + 1].timestamp)
        sum / (feedings.size - 1)
    } else null

    val perDay = (6 downTo 0).map { back ->
        val start = todayStart - back * dayMs
        val end = start + dayMs
        dayShortFormat().format(Date(start)) to cries.count { it.timestamp in start until end }
    }

    return HistorySummary(
        lastFeedAgoMs = lastFeed?.let { now - it },
        nextFeedInMs = nextFeedInMs,
        lastCryAgoMs = lastCry?.let { now - it.timestamp },
        lastCryReason = lastCry?.let { reasonOf(it, labels) },
        criesToday = cries.count { it.timestamp >= todayStart },
        criesYesterday = cries.count { it.timestamp in (todayStart - dayMs) until todayStart },
        feedsToday = feedings.count { it.timestamp >= todayStart },
        topReasonToday = topReasonToday,
        peakHour = peakHour,
        avgFeedGapMs = avgFeedGapMs,
        perDay = perDay,
        perHour = perHour.toList(),
    )
}

private fun buildTimeline(cries: List<CryEvent>, feedings: List<FeedingEvent>, now: Long): List<Line> {
    val entries = ArrayList<Pair<Long, Line>>(cries.size + feedings.size)
    cries.forEach { entries += it.timestamp to Line.Cry(it) }
    feedings.forEach { entries += it.timestamp to Line.Feed(it) }
    entries.sortByDescending { it.first }

    val out = ArrayList<Line>()
    val today = startOfDay(now)
    var lastDay = Long.MIN_VALUE
    for ((ts, line) in entries.take(80)) {
        val ds = startOfDay(ts)
        if (ds != lastDay) {
            out += Line.Header(dayLabel(ds, today))
            lastDay = ds
        }
        out += line
    }
    return out
}

private fun dayLabel(dayStart: Long, todayStart: Long): String = when (dayStart) {
    todayStart -> if (currentAppLang == AppLang.EN) "Today" else "Σήμερα"
    todayStart - 86_400_000L -> if (currentAppLang == AppLang.EN) "Yesterday" else "Χθες"
    else -> dayHeaderFormat().format(Date(dayStart)).replaceFirstChar { it.uppercase() }
}

private fun startOfDay(ts: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

private fun relativeAgo(ms: Long): String {
    val min = ms / 60_000L
    return when (currentAppLang) {
        AppLang.EN -> when {
            min < 1 -> "just now"
            min < 60 -> "${min}m ago"
            min < 1440 -> {
                val h = min / 60
                val m = min % 60
                if (m == 0L) "${h}h ago" else "${h}h ${m}m ago"
            }
            else -> "${min / 1440}d ago"
        }
        AppLang.EL -> when {
            min < 1 -> "μόλις τώρα"
            min < 60 -> "πριν $min′"
            min < 1440 -> {
                val h = min / 60
                val m = min % 60
                if (m == 0L) "πριν ${h}ω" else "πριν ${h}ω $m′"
            }
            else -> "πριν ${min / 1440} ημ."
        }
    }
}

private fun durationShort(ms: Long): String {
    val min = (if (ms < 0) 0 else ms) / 60_000L
    return when (currentAppLang) {
        AppLang.EN -> {
            if (min < 60) return "${min}m"
            val h = min / 60
            val m = min % 60
            if (m == 0L) "${h}h" else "${h}h ${m}m"
        }
        AppLang.EL -> {
            if (min < 60) return "$min′"
            val h = min / 60
            val m = min % 60
            if (m == 0L) "${h}ω" else "${h}ω $m′"
        }
    }
}
