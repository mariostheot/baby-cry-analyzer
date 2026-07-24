package com.babycry.analyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.context.ContextPrior
import com.babycry.analyzer.data.CryEvent
import com.babycry.analyzer.data.DiaperEvent
import com.babycry.analyzer.data.FeedingEvent
import com.babycry.analyzer.data.HeightEvent
import com.babycry.analyzer.data.SleepEvent
import com.babycry.analyzer.data.TummyTimeEvent
import com.babycry.analyzer.data.WeightEvent
import com.babycry.analyzer.model.BabyProfile
import com.babycry.analyzer.model.CryReason
import com.babycry.analyzer.model.DiaperType
import com.babycry.analyzer.model.TummyTime
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.tr
import com.babycry.analyzer.ui.i18n.translate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun displayLocale(): Locale =
    if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")

private fun dayHeaderFormat() = SimpleDateFormat("EEEE d/M", displayLocale())
private fun dayShortFormat() = SimpleDateFormat("EEE", displayLocale())

@Composable
fun HistoryScreen(viewModel: CryViewModel, modifier: Modifier = Modifier) {
    val events by viewModel.recentEvents.collectAsState()
    val feedings by viewModel.recentFeedings.collectAsState()
    val sleeps by viewModel.recentSleep.collectAsState()
    val diapers by viewModel.recentDiapers.collectAsState()
    val tummy by viewModel.recentTummy.collectAsState()
    val weights by viewModel.recentWeights.collectAsState()
    val heights by viewModel.recentHeights.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val labels = viewModel.labels
    val language by viewModel.language.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var editEvent by remember { mutableStateOf<CryEvent?>(null) }
    var editFeeding by remember { mutableStateOf<FeedingEvent?>(null) }
    var editSleep by remember { mutableStateOf<SleepEvent?>(null) }
    var editDiaper by remember { mutableStateOf<DiaperEvent?>(null) }
    var editTummy by remember { mutableStateOf<TummyTimeEvent?>(null) }
    var editWeight by remember { mutableStateOf<WeightEvent?>(null) }
    var editHeight by remember { mutableStateOf<HeightEvent?>(null) }
    var pendingDelete by remember { mutableStateOf<CryEvent?>(null) }
    var timelineFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    var selectedDayStart by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var addKind by remember { mutableStateOf<HistoryAddKind?>(null) }

    // A measurement belongs to one baby. Do not leave an edit dialog open across a switch.
    // Also reset the selected day so Baby B does not inherit Baby A's day (often empty).
    LaunchedEffect(profile.id) {
        editWeight = null
        editHeight = null
        editFeeding = null
        editSleep = null
        editDiaper = null
        editTummy = null
        editEvent = null
        pendingDelete = null
        addKind = null
        confirmClear = false
        selectedDayStart = Long.MIN_VALUE
    }

    val now = System.currentTimeMillis()
    val nowTick = rememberMidnightTick()
    val todayStart = startOfDay(nowTick)
    val cries = remember(events) { events.filter { it.cryDetected } }
    val summary = remember(events, feedings, sleeps, diapers, tummy, profile, language, nowTick) {
        computeSummary(cries, feedings, sleeps, diapers, tummy, labels, profile, System.currentTimeMillis())
    }
    val hasCareLog = cries.isNotEmpty() || feedings.isNotEmpty() || sleeps.isNotEmpty() ||
        diapers.isNotEmpty() || tummy.isNotEmpty()
    val hasMeasurements = weights.isNotEmpty() || heights.isNotEmpty()
    val loggedDays = remember(cries, feedings, sleeps, diapers, tummy, weights, heights) {
        collectLoggedDays(cries, feedings, sleeps, diapers, tummy, weights, heights)
    }
    val defaultDay = remember(loggedDays, todayStart) {
        if (loggedDays.contains(todayStart)) todayStart else loggedDays.maxOrNull() ?: todayStart
    }
    LaunchedEffect(profile.id, defaultDay) {
        if (selectedDayStart == Long.MIN_VALUE) {
            selectedDayStart = defaultDay
        }
    }
    val activeDayStart = if (selectedDayStart == Long.MIN_VALUE) defaultDay else selectedDayStart
    val daySummary = remember(cries, feedings, sleeps, diapers, tummy, labels, profile, activeDayStart, language, now) {
        computeDaySummary(cries, feedings, sleeps, diapers, tummy, labels, profile, activeDayStart, now)
    }
    val dayLines = remember(cries, feedings, sleeps, diapers, tummy, activeDayStart, language, now) {
        buildDayTimeline(cries, feedings, sleeps, diapers, tummy, activeDayStart, now)
    }
    val visibleDayLines = remember(dayLines, timelineFilter) {
        dayLines.filter(timelineFilter::matches)
    }

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
                if (hasCareLog || hasMeasurements) {
                    TextButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text(tr("Καθαρισμός"))
                    }
                }
            }
        }

        if (!hasCareLog && !hasMeasurements) {
            item {
                Text(tr("Δεν υπάρχουν ακόμα καταγραφές. Πρόσθεσε φροντίδα παρακάτω, ή πάτα «Άκου το μωρό»."))
            }
            item {
                DayNavigationBar(
                    selectedDayStart = activeDayStart,
                    todayStart = todayStart,
                    onPrevious = { selectedDayStart = previousDayStart(activeDayStart) },
                    onNext = {
                        if (activeDayStart < todayStart) {
                            selectedDayStart = minOf(nextDayStart(activeDayStart), todayStart)
                        }
                    },
                    onToday = { selectedDayStart = todayStart },
                )
            }
            item { DayAddCareBar(onAdd = { addKind = it }) }
        } else {
            item { LiveTiles(summary) }
            item {
                DayNavigationBar(
                    selectedDayStart = activeDayStart,
                    todayStart = todayStart,
                    onPrevious = { selectedDayStart = previousDayStart(activeDayStart) },
                    onNext = {
                        if (activeDayStart < todayStart) {
                            selectedDayStart = minOf(nextDayStart(activeDayStart), todayStart)
                        }
                    },
                    onToday = { selectedDayStart = todayStart },
                )
            }
            item { DaySummaryCard(daySummary) }
            item { DayAddCareBar(onAdd = { addKind = it }) }
            item {
                HistoryFilterBar(
                    selected = timelineFilter,
                    onSelected = { timelineFilter = it },
                )
            }
            item {
                Text(
                    tr("Πάτησε μια καταγραφή για να διορθώσεις ώρα, τύπο ή διάρκεια."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (visibleDayLines.isEmpty()) {
                item {
                    Text(
                        tr(
                            if (timelineFilter == HistoryFilter.ALL) {
                                "Δεν υπάρχουν καταγραφές αυτή την ημέρα."
                            } else {
                                "Δεν υπάρχουν καταγραφές για αυτό το φίλτρο αυτή την ημέρα."
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                itemsIndexed(
                    visibleDayLines,
                    key = { _, line ->
                        when (line) {
                            is Line.Cry -> "c_${line.e.id}"
                            is Line.Feed -> "f_${line.e.id}"
                            is Line.Sleep -> "s_${line.e.id}"
                            is Line.Diaper -> "d_${line.e.id}"
                            is Line.Tummy -> "t_${line.e.id}"
                        }
                    },
                ) { index, line ->
                    val isFirst = index == 0
                    val isLast = index == visibleDayLines.lastIndex
                    when (line) {
                        is Line.Cry -> CryTimelineRow(
                            event = line.e,
                            labels = labels,
                            isFirst = isFirst,
                            isLast = isLast,
                            onEdit = { editEvent = line.e },
                            onDelete = { pendingDelete = line.e },
                        )
                        is Line.Feed -> FeedTimelineRow(
                            feeding = line.e,
                            dayStart = activeDayStart,
                            isFirst = isFirst,
                            isLast = isLast,
                            onEdit = { editFeeding = line.e },
                        )
                        is Line.Sleep -> SleepTimelineRow(
                            sleep = line.e,
                            dayStart = activeDayStart,
                            isFirst = isFirst,
                            isLast = isLast,
                            onEdit = { editSleep = line.e },
                        )
                        is Line.Diaper -> DiaperTimelineRow(
                            diaper = line.e,
                            isFirst = isFirst,
                            isLast = isLast,
                            onEdit = { editDiaper = line.e },
                        )
                        is Line.Tummy -> TummyTimelineRow(
                            tummy = line.e,
                            isFirst = isFirst,
                            isLast = isLast,
                            onEdit = { editTummy = line.e },
                        )
                    }
                }
            }
            item { PatternsCard(summary) }
        }

        item {
            Text(
                tr("Βάρος & ύψος"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        item {
            WeightHistoryCard(
                weights = weights,
                onEdit = { editWeight = it },
            )
        }
        item {
            HeightHistoryCard(
                heights = heights,
                onEdit = { editHeight = it },
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Καθαρισμός ιστορικού;")) },
            text = {
                Text(tr("Θα διαγραφούν όλα τα καταγεγραμμένα κλάματα (μαζί με τις αποθηκευμένες ηχογραφήσεις), τα ταΐσματα, οι ύπνοι, οι αλλαγές πάνας, το tummy time, οι μετρήσεις βάρους/ύψους και τα γραφήματα. Αυτό που έμαθε το μοντέλο από εσένα ΔΕΝ επηρεάζεται."))
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

    editFeeding?.let { feeding ->
        val isRunning = feeding.durationMs < 0L
        val dayStart = startOfDay(feeding.timestamp)
        var startTime by remember(feeding.id, feeding.timestamp) {
            mutableStateOf(ClockTime12.fromMillis(feeding.timestamp))
        }
        var endTime by remember(feeding.id, feeding.timestamp, feeding.durationMs) {
            mutableStateOf(
                ClockTime12.fromMillis(feeding.timestamp + feeding.durationMs.coerceAtLeast(0L)),
            )
        }
        val updatedStartedAt = if (startTime.isValid) atDayTime(dayStart, startTime) else feeding.timestamp
        val endMillis = if (endTime.isValid) atDayTime(dayStart, endTime) else feeding.timestamp
        // Support a feed that crosses midnight (end earlier clock than start = next calendar day).
        val durationMs = durationCrossingMidnight(updatedStartedAt, endMillis)
        val endedAt = updatedStartedAt + durationMs
        val timesOk = startTime.isValid && endTime.isValid && durationMs > 0L
        val notFuture = updatedStartedAt <= now && endedAt <= now
        val valid = timesOk && notFuture

        AlertDialog(
            onDismissRequest = { editFeeding = null },
            title = { Text(tr("Επεξεργασία ταΐσματος")) },
            text = {
                if (isRunning) {
                    Text(
                        tr("Το τάισμα είναι σε εξέλιξη") + ". " +
                            tr("Πάτησε το κουμπί στην αρχική για να το σταματήσεις."),
                    )
                } else {
                    Column {
                        Time12Row(
                            label = tr("Ώρα έναρξης"),
                            time = startTime,
                            onTimeChange = { startTime = it },
                        )
                        Spacer(Modifier.height(12.dp))
                        Time12Row(
                            label = tr("Ώρα λήξης"),
                            time = endTime,
                            onTimeChange = { endTime = it },
                        )
                        if (timesOk && !notFuture) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                tr("Δεν μπορείς να καταγράψεις ώρα στο μέλλον."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!isRunning) {
                    TextButton(
                        onClick = {
                            viewModel.updateFeeding(feeding.id, updatedStartedAt, durationMs)
                            editFeeding = null
                        },
                        enabled = valid,
                    ) { Text(tr("Αποθήκευση")) }
                }
            },
            dismissButton = {
                TextButton(onClick = { editFeeding = null }) { Text(tr("Κλείσιμο")) }
            },
        )
    }

    editSleep?.let { sleepEvent ->
        val isRunning = sleepEvent.durationMs < 0L
        val dayStart = startOfDay(sleepEvent.timestamp)
        var startTime by remember(sleepEvent.id, sleepEvent.timestamp) {
            mutableStateOf(ClockTime12.fromMillis(sleepEvent.timestamp))
        }
        var endTime by remember(sleepEvent.id, sleepEvent.timestamp, sleepEvent.durationMs) {
            mutableStateOf(
                ClockTime12.fromMillis(sleepEvent.timestamp + sleepEvent.durationMs.coerceAtLeast(0L)),
            )
        }
        val updatedStartedAt = if (startTime.isValid) atDayTime(dayStart, startTime) else sleepEvent.timestamp
        val endMillis = if (endTime.isValid) atDayTime(dayStart, endTime) else sleepEvent.timestamp
        val durationMs = durationCrossingMidnight(updatedStartedAt, endMillis)
        val endedAt = updatedStartedAt + durationMs
        val timesOk = startTime.isValid && endTime.isValid && durationMs > 0L
        val notFuture = updatedStartedAt <= now && endedAt <= now
        val valid = timesOk && notFuture

        AlertDialog(
            onDismissRequest = { editSleep = null },
            title = { Text(tr("Επεξεργασία ύπνου")) },
            text = {
                if (isRunning) {
                    Text(
                        tr("Ο ύπνος είναι σε εξέλιξη") + ". " +
                            tr("Πάτησε το κουμπί στην αρχική για να τον σταματήσεις."),
                    )
                } else {
                    Column {
                        Time12Row(
                            label = tr("Ώρα έναρξης"),
                            time = startTime,
                            onTimeChange = { startTime = it },
                        )
                        Spacer(Modifier.height(12.dp))
                        Time12Row(
                            label = tr("Ώρα λήξης"),
                            time = endTime,
                            onTimeChange = { endTime = it },
                        )
                        if (timesOk && !notFuture) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                tr("Δεν μπορείς να καταγράψεις ώρα στο μέλλον."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (!isRunning) {
                    TextButton(
                        onClick = {
                            viewModel.updateSleep(sleepEvent.id, updatedStartedAt, durationMs)
                            editSleep = null
                        },
                        enabled = valid,
                    ) { Text(tr("Αποθήκευση")) }
                }
            },
            dismissButton = {
                TextButton(onClick = { editSleep = null }) { Text(tr("Κλείσιμο")) }
            },
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

    editDiaper?.let { diaper ->
        AddDiaperOnDayDialog(
            dayStart = startOfDay(diaper.timestamp),
            initial = diaper,
            onDismiss = { editDiaper = null },
            onSave = { type, timestamp ->
                viewModel.updateDiaper(diaper.id, type, timestamp)
                editDiaper = null
            },
            onDelete = {
                viewModel.deleteDiaper(diaper.id)
                editDiaper = null
            },
        )
    }

    editTummy?.let { tummyEvent ->
        AddTimedOnDayDialog(
            title = tr("Επεξεργασία tummy time"),
            dayStart = startOfDay(tummyEvent.timestamp),
            initialTimestamp = tummyEvent.timestamp,
            onDismiss = { editTummy = null },
            onSave = { timestamp ->
                viewModel.updateTummy(tummyEvent.id, timestamp)
                editTummy = null
            },
            onDelete = {
                viewModel.deleteTummy(tummyEvent.id)
                editTummy = null
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

    when (val kind = addKind) {
        HistoryAddKind.FEED -> AddCompletedSessionDialog(
            title = tr("Προσθήκη ταΐσματος"),
            dayStart = activeDayStart,
            defaultDurationMinutes = 15,
            onDismiss = { addKind = null },
            onSave = { startedAt, durationMs ->
                viewModel.addCompletedFeeding(startedAt, durationMs)
                addKind = null
            },
        )
        HistoryAddKind.SLEEP -> AddCompletedSessionDialog(
            title = tr("Προσθήκη ύπνου"),
            dayStart = activeDayStart,
            defaultDurationMinutes = 60,
            onDismiss = { addKind = null },
            onSave = { startedAt, durationMs ->
                viewModel.addCompletedSleep(startedAt, durationMs)
                addKind = null
            },
        )
        HistoryAddKind.DIAPER -> AddDiaperOnDayDialog(
            dayStart = activeDayStart,
            onDismiss = { addKind = null },
            onSave = { type, timestamp ->
                viewModel.logDiaper(type, timestamp)
                addKind = null
            },
        )
        HistoryAddKind.TUMMY -> AddTimedOnDayDialog(
            title = tr("Προσθήκη tummy time"),
            dayStart = activeDayStart,
            onDismiss = { addKind = null },
            onSave = { timestamp ->
                viewModel.logTummy(timestamp)
                addKind = null
            },
        )
        HistoryAddKind.WEIGHT -> WeightEntryDialog(
            initial = null,
            initialTimestamp = defaultInstantOnDay(activeDayStart, System.currentTimeMillis()),
            onDismiss = { addKind = null },
            onSave = { grams, timestamp ->
                viewModel.addWeight(grams, timestamp)
                addKind = null
            },
            onDelete = null,
        )
        HistoryAddKind.HEIGHT -> HeightEntryDialog(
            initial = null,
            initialTimestamp = defaultInstantOnDay(activeDayStart, System.currentTimeMillis()),
            onDismiss = { addKind = null },
            onSave = { millimeters, timestamp ->
                viewModel.addHeight(millimeters, timestamp)
                addKind = null
            },
            onDelete = null,
        )
        null -> Unit
    }
}

@Composable
private fun LiveTiles(s: HistorySummary) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val overdue = s.nextFeedInMs != null && s.nextFeedInMs <= 0
            Tile(
                modifier = Modifier.weight(1f),
                emoji = "🍼",
                title = tr("Τελευταίο τάισμα"),
                big = when {
                    s.feedingNow -> tr("τώρα")
                    else -> s.lastFeedAgoMs?.let(::relativeAgo) ?: "—"
                },
                subtitle = when {
                    s.feedingNow -> tr("ταΐζεται τώρα")
                    s.lastFeedAgoMs == null -> tr("δεν έχει καταγραφεί")
                    s.nextFeedInMs == null -> ""
                    overdue -> tr("ίσως πεινάει")
                    else -> nextFeedSubtitle(s.nextFeedInMs)
                },
                subtitleAccent = overdue,
            )
            Tile(
                modifier = Modifier.weight(1f),
                emoji = "😴",
                title = tr("Τελευταίος ύπνος"),
                big = when {
                    s.sleepingNow -> tr("τώρα")
                    else -> s.lastSleepAgoMs?.let(::relativeAgo) ?: "—"
                },
                subtitle = when {
                    s.sleepingNow -> tr("κοιμάται τώρα")
                    s.lastSleepAgoMs == null -> tr("δεν έχει καταγραφεί")
                    else -> ""
                },
                subtitleAccent = false,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayAddCareBar(onAdd: (HistoryAddKind) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            tr("Προσθήκη στην ημέρα"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onAdd(HistoryAddKind.FEED) }) {
                Text("🍼 " + tr("Τάισμα"), maxLines = 1)
            }
            OutlinedButton(onClick = { onAdd(HistoryAddKind.DIAPER) }) {
                Text("🧷 " + tr("Πάνα"), maxLines = 1)
            }
            OutlinedButton(onClick = { onAdd(HistoryAddKind.SLEEP) }) {
                Text("😴 " + tr("Ύπνος"), maxLines = 1)
            }
            OutlinedButton(onClick = { onAdd(HistoryAddKind.TUMMY) }) {
                Text("🧸 " + tr("Tummy time"), maxLines = 1)
            }
            OutlinedButton(onClick = { onAdd(HistoryAddKind.WEIGHT) }) {
                Text("⚖️ " + tr("Βάρος"), maxLines = 1)
            }
            OutlinedButton(onClick = { onAdd(HistoryAddKind.HEIGHT) }) {
                Text("📏 " + tr("Ύψος"), maxLines = 1)
            }
        }
    }
}

/**
 * Manual entry for a completed feed/sleep on [dayStart] (when the live timer was missed).
 * Times use 12-hour π.μ./μ.μ. Duration defaults to [defaultDurationMinutes].
 */
@Composable
private fun AddCompletedSessionDialog(
    title: String,
    dayStart: Long,
    defaultDurationMinutes: Int,
    onDismiss: () -> Unit,
    onSave: (startedAt: Long, durationMs: Long) -> Unit,
) {
    val now = System.currentTimeMillis()
    val defaults = remember(dayStart, defaultDurationMinutes) {
        defaultSessionTimes(dayStart, now, defaultDurationMinutes)
    }
    var startTime by remember(dayStart) {
        mutableStateOf(ClockTime12.fromHour24(defaults.startHour, defaults.startMinute))
    }
    var endTime by remember(dayStart) {
        mutableStateOf(ClockTime12.fromHour24(defaults.endHour, defaults.endMinute))
    }

    val startedAt = if (startTime.isValid) atDayTime(dayStart, startTime) else 0L
    val endSameDay = if (endTime.isValid) atDayTime(dayStart, endTime) else 0L
    val durationMs = durationCrossingMidnight(startedAt, endSameDay)
    val endedAt = startedAt + durationMs
    val timesOk = startTime.isValid && endTime.isValid
    val notFuture = startedAt <= now && endedAt <= now
    val onSelectedDay = startedAt >= dayStart && startedAt < nextDayStart(dayStart)
    val valid = timesOk && durationMs > 0L && notFuture && onSelectedDay

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    tr("Για όταν ξέχασες να πατήσεις έναρξη στην Αρχική. Βάλε ώρα έναρξης και λήξης."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(12.dp))
                Time12Row(
                    label = tr("Ώρα έναρξης"),
                    time = startTime,
                    onTimeChange = { startTime = it },
                )
                Spacer(Modifier.height(12.dp))
                Time12Row(
                    label = tr("Ώρα λήξης"),
                    time = endTime,
                    onTimeChange = { endTime = it },
                )
                if (timesOk && !valid) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            !onSelectedDay -> tr("Η έναρξη πρέπει να είναι στην επιλεγμένη ημέρα.")
                            durationMs <= 0L -> tr("Η λήξη πρέπει να είναι μετά την έναρξη.")
                            else -> tr("Δεν μπορείς να καταγράψεις ώρα στο μέλλον.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(startedAt, durationMs) },
                enabled = valid,
            ) { Text(tr("Αποθήκευση")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Άκυρο")) }
        },
    )
}

@Composable
private fun AddDiaperOnDayDialog(
    dayStart: Long,
    onDismiss: () -> Unit,
    onSave: (DiaperType, Long) -> Unit,
    initial: DiaperEvent? = null,
    onDelete: (() -> Unit)? = null,
) {
    val now = System.currentTimeMillis()
    val defaults = remember(dayStart, initial?.id) {
        if (initial != null) {
            val c = ClockTime12.fromMillis(initial.timestamp)
            DefaultSessionTimes(c.hour24, c.minute, c.hour24, c.minute)
        } else {
            defaultSessionTimes(dayStart, now, 0)
        }
    }
    var clock by remember(dayStart, initial?.id) {
        mutableStateOf(ClockTime12.fromHour24(defaults.startHour, defaults.startMinute))
    }
    var selectedType by remember(initial?.id) {
        mutableStateOf(initial?.let { DiaperType.fromNameOrNull(it.type) })
    }

    val timestamp = if (clock.isValid) atDayTime(dayStart, clock) else 0L
    val valid = clock.isValid &&
        selectedType != null &&
        timestamp <= now &&
        timestamp >= dayStart &&
        timestamp < nextDayStart(dayStart)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (initial == null) "Προσθήκη πάνας" else "Επεξεργασία πάνας")) },
        text = {
            Column {
                Time12Row(
                    label = tr("Ώρα"),
                    time = clock,
                    onTimeChange = { clock = it },
                )
                Spacer(Modifier.height(12.dp))
                Text(tr("Τι είχε η πάνα;"), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                DiaperType.entries.forEach { t ->
                    OutlinedButton(
                        onClick = { selectedType = t },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            (if (selectedType == t) "✓ " else "") + "${t.emoji}  ${tr(t.displayName)}",
                            maxLines = 1,
                        )
                    }
                }
                if (clock.isValid && selectedType != null && !valid) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Δεν μπορείς να καταγράψεις ώρα στο μέλλον."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val type = selectedType ?: return@TextButton
                    onSave(type, timestamp)
                },
                enabled = valid,
            ) { Text(tr("Αποθήκευση")) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(tr("Διαγραφή")) }
                }
                TextButton(onClick = onDismiss) { Text(tr(if (onDelete != null) "Κλείσιμο" else "Άκυρο")) }
            }
        },
    )
}

@Composable
private fun AddTimedOnDayDialog(
    title: String,
    dayStart: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
    initialTimestamp: Long? = null,
    onDelete: (() -> Unit)? = null,
) {
    val now = System.currentTimeMillis()
    val defaults = remember(dayStart, initialTimestamp) {
        if (initialTimestamp != null) {
            val c = ClockTime12.fromMillis(initialTimestamp)
            DefaultSessionTimes(c.hour24, c.minute, c.hour24, c.minute)
        } else {
            defaultSessionTimes(dayStart, now, 0)
        }
    }
    var clock by remember(dayStart, initialTimestamp) {
        mutableStateOf(ClockTime12.fromHour24(defaults.startHour, defaults.startMinute))
    }
    val timestamp = if (clock.isValid) atDayTime(dayStart, clock) else 0L
    val valid = clock.isValid &&
        timestamp <= now &&
        timestamp >= dayStart &&
        timestamp < nextDayStart(dayStart)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    tr("Βάλε την ώρα που έγινε, αν δεν το κατέγραψες τη στιγμή εκείνη."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(12.dp))
                Time12Row(
                    label = tr("Ώρα"),
                    time = clock,
                    onTimeChange = { clock = it },
                )
                if (clock.isValid && !valid) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Δεν μπορείς να καταγράψεις ώρα στο μέλλον."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(timestamp) },
                enabled = valid,
            ) { Text(tr("Αποθήκευση")) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(tr("Διαγραφή")) }
                }
                TextButton(onClick = onDismiss) { Text(tr(if (onDelete != null) "Κλείσιμο" else "Άκυρο")) }
            }
        },
    )
}

private data class DefaultSessionTimes(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
)

private fun defaultInstantOnDay(dayStart: Long, now: Long): Long {
    val t = defaultSessionTimes(dayStart, now, 0)
    return atDayTime(dayStart, ClockTime12.fromHour24(t.startHour, t.startMinute))
}

private fun defaultSessionTimes(dayStart: Long, now: Long, durationMinutes: Int): DefaultSessionTimes {
    val durationMs = durationMinutes.coerceAtLeast(0) * 60_000L
    val start = Calendar.getInstance()
    val end = Calendar.getInstance()
    if (startOfDay(now) == dayStart) {
        end.timeInMillis = now
        start.timeInMillis = (now - durationMs).coerceAtLeast(dayStart)
    } else {
        start.timeInMillis = dayStart
        start.set(Calendar.HOUR_OF_DAY, 12)
        start.set(Calendar.MINUTE, 0)
        end.timeInMillis = start.timeInMillis + durationMs
    }
    start.set(Calendar.SECOND, 0)
    start.set(Calendar.MILLISECOND, 0)
    end.set(Calendar.SECOND, 0)
    end.set(Calendar.MILLISECOND, 0)
    return DefaultSessionTimes(
        startHour = start.get(Calendar.HOUR_OF_DAY),
        startMinute = start.get(Calendar.MINUTE),
        endHour = end.get(Calendar.HOUR_OF_DAY),
        endMinute = end.get(Calendar.MINUTE),
    )
}

@Composable
private fun DayNavigationBar(
    selectedDayStart: Long,
    todayStart: Long,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val onTodaySelected = selectedDayStart == todayStart
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = tr("Προηγούμενη ημέρα"),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                dayLabel(selectedDayStart, todayStart),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                dayHeaderFormat().format(Date(selectedDayStart)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        IconButton(
            onClick = onNext,
            enabled = selectedDayStart < todayStart,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = tr("Επόμενη ημέρα"),
            )
        }
    }
    if (!onTodaySelected) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onToday) {
                Text(tr("Σήμερα"))
            }
        }
    }
}

private enum class HistoryAddKind { FEED, SLEEP, DIAPER, TUMMY, WEIGHT, HEIGHT }

private enum class HistoryFilter(val label: String, val emoji: String) {
    ALL("Όλα", "•"),
    CRIES("Κλάματα", "😢"),
    FEEDINGS("Ταΐσματα", "🍼"),
    SLEEP("Ύπνος", "😴"),
    DIAPERS("Πάνες", "🧷"),
    TUMMY("Tummy Time", "🤸");

    fun matches(line: Line): Boolean = when (this) {
        ALL -> true
        CRIES -> line is Line.Cry
        FEEDINGS -> line is Line.Feed
        SLEEP -> line is Line.Sleep
        DIAPERS -> line is Line.Diaper
        TUMMY -> line is Line.Tummy
    }
}

@Composable
private fun HistoryFilterBar(
    selected: HistoryFilter,
    onSelected: (HistoryFilter) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        items(HistoryFilter.entries) { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelected(filter) },
                label = { Text("${filter.emoji} ${tr(filter.label)}") },
            )
        }
    }
}

@Composable
private fun DaySummaryCard(s: DaySummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                DaySummaryChip("😢", s.cries.toString(), tr("Κλάματα"))
                DaySummaryChip("🍼", s.feeds.toString(), tr("Ταΐσματα"))
                DaySummaryChip("😴", s.sleeps.toString(), tr("Ύπνος"))
                DaySummaryChip(
                    "🧷",
                    if (s.poops > 0) "${s.diapers}·💩${s.poops}" else s.diapers.toString(),
                    tr("Πάνες"),
                )
                DaySummaryChip("🤸", "${s.tummy}/${s.tummyGoal}", tr("Tummy Time"))
            }
            if (s.topReason != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${tr("Πιο συχνή αιτία")}: ${s.topReason.emoji} ${tr(s.topReason.displayName)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            if (s.criesYesterdayComparison != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    s.criesYesterdayComparison,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun DaySummaryChip(emoji: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            maxLines = 1,
        )
    }
}

@Composable
private fun TimelineConnector(
    color: Color,
    isFirst: Boolean,
    isLast: Boolean,
) {
    Box(
        Modifier
            .width(28.dp)
            .height(IntrinsicSize.Min),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (!isFirst) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight(0.5f)
                    .align(Alignment.TopCenter)
                    .background(color.copy(alpha = 0.35f)),
            )
        }
        Box(
            Modifier
                .padding(top = 10.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        if (!isLast) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .padding(top = 20.dp)
                    .align(Alignment.TopCenter)
                    .background(color.copy(alpha = 0.35f)),
            )
        }
    }
}

@Composable
private fun TimelineRowShell(
    timestamp: Long,
    color: Color,
    isFirst: Boolean,
    isLast: Boolean,
    icon: String,
    label: String,
    metadata: String?,
    onClick: (() -> Unit)?,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            formatTime12(timestamp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier
                .width(72.dp)
                .padding(top = 8.dp),
        )
        TimelineConnector(color = color, isFirst = isFirst, isLast = isLast)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$icon  $label",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            if (!metadata.isNullOrBlank()) {
                Text(
                    metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun CryTimelineRow(
    event: CryEvent,
    labels: List<CryReason>,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val reason = reasonOf(event, labels)
    val label = reason?.let { "${it.emoji} ${tr(it.displayName)}" } ?: tr("Κλάμα")
    val metadata = "${(event.confidence * 100).roundToInt()}%"
    TimelineRowShell(
        timestamp = event.timestamp,
        color = MaterialTheme.colorScheme.primary,
        isFirst = isFirst,
        isLast = isLast,
        icon = "😢",
        label = label,
        metadata = metadata,
        onClick = onEdit,
        trailing = {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = tr("Διαγραφή"),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

@Composable
private fun FeedTimelineRow(
    feeding: FeedingEvent,
    dayStart: Long,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
) {
    val isRunning = feeding.durationMs < 0L
    val metadata = when {
        isRunning -> tr("Το τάισμα είναι σε εξέλιξη")
        feeding.durationMs > 0L -> {
            val endTime = formatTime12(feeding.timestamp + feeding.durationMs)
            "${feedingDurationText(feeding.durationMs)} · $endTime"
        }
        else -> null
    }
    TimelineRowShell(
        timestamp = displayTimeOnDay(feeding.timestamp, feeding.durationMs, dayStart),
        color = MaterialTheme.colorScheme.secondary,
        isFirst = isFirst,
        isLast = isLast,
        icon = "🍼",
        label = tr("Τάισμα"),
        metadata = metadata,
        onClick = onEdit,
    )
}

@Composable
private fun SleepTimelineRow(
    sleep: SleepEvent,
    dayStart: Long,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
) {
    val isRunning = sleep.durationMs < 0L
    val metadata = when {
        isRunning -> tr("Ο ύπνος είναι σε εξέλιξη")
        sleep.durationMs > 0L -> {
            val endTime = formatTime12(sleep.timestamp + sleep.durationMs)
            "${sleepDurationText(sleep.durationMs)} · $endTime"
        }
        else -> null
    }
    TimelineRowShell(
        timestamp = displayTimeOnDay(sleep.timestamp, sleep.durationMs, dayStart),
        color = MaterialTheme.colorScheme.tertiary,
        isFirst = isFirst,
        isLast = isLast,
        icon = "😴",
        label = tr("Ύπνος"),
        metadata = metadata,
        onClick = onEdit,
    )
}

@Composable
private fun DiaperTimelineRow(
    diaper: DiaperEvent,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
) {
    val type = DiaperType.fromNameOrNull(diaper.type) ?: DiaperType.WET
    TimelineRowShell(
        timestamp = diaper.timestamp,
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
        isFirst = isFirst,
        isLast = isLast,
        icon = type.emoji,
        label = tr("Πάνα"),
        metadata = tr(type.displayName),
        onClick = onEdit,
    )
}

@Composable
private fun TummyTimelineRow(
    tummy: TummyTimeEvent,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
) {
    TimelineRowShell(
        timestamp = tummy.timestamp,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        isFirst = isFirst,
        isLast = isLast,
        icon = "🤸",
        label = tr("Tummy Time"),
        metadata = null,
        onClick = onEdit,
    )
}

private fun criesComparisonForDay(diff: Int, previousDay: Int): String = when (currentAppLang) {
    AppLang.EN -> when {
        diff < 0 -> "↓ fewer than previous day ($previousDay)"
        diff > 0 -> "↑ more than previous day ($previousDay)"
        else -> "≈ same as previous day ($previousDay)"
    }
    AppLang.EL -> when {
        diff < 0 -> "↓ λιγότερα από την προηγούμενη ημέρα ($previousDay)"
        diff > 0 -> "↑ περισσότερα από την προηγούμενη ημέρα ($previousDay)"
        else -> "≈ ίδια με την προηγούμενη ημέρα ($previousDay)"
    }
}

@Composable
private fun feedingDurationText(durationMs: Long): String {
    val minutes = ((durationMs.coerceAtLeast(0L) + 30_000L) / 60_000L)
    return when (currentAppLang) {
        AppLang.EN -> "$minutes min"
        AppLang.EL -> "$minutes λεπτά"
    }
}

@Composable
private fun sleepDurationText(durationMs: Long): String {
    val minutes = ((durationMs.coerceAtLeast(0L) + 30_000L) / 60_000L)
    return when (currentAppLang) {
        AppLang.EN -> "$minutes min"
        AppLang.EL -> "$minutes λεπτά"
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
                s.peakHour?.let { formatHourRange12(it, (it + 1) % 24) } ?: "—",
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
            DayBars(s.perDay, MaterialTheme.colorScheme.primary)

            if (s.diaperPerDay.any { it.second > 0 }) {
                Spacer(Modifier.height(14.dp))
                Text(tr("Πάνες ανά ημέρα (τελευταίες 7)"), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                DayBars(s.diaperPerDay, MaterialTheme.colorScheme.tertiary)
            }

            if (s.tummyPerDay.any { it.second > 0 }) {
                Spacer(Modifier.height(14.dp))
                Text(tr("Tummy time ανά ημέρα (τελευταίες 7)"), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                DayBars(s.tummyPerDay, MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A tiny 7-day bar chart (count on top, day label below). Reused for cries and diapers. */
@Composable
private fun DayBars(data: List<Pair<String, Int>>, color: Color) {
    val maxDay = (data.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(80.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        data.forEach { (label, count) ->
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
                        .background(color),
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
                "${tr("Ώρα αιχμής:")} ${formatHourRange12(peak, (peak + 1) % 24)}",
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

private data class DaySummary(
    val cries: Int,
    val feeds: Int,
    val sleeps: Int,
    val diapers: Int,
    val poops: Int,
    val tummy: Int,
    val tummyGoal: Int,
    val topReason: CryReason?,
    val criesYesterdayComparison: String?,
)

private sealed interface Line {
    data class Cry(val e: CryEvent) : Line
    data class Feed(val e: FeedingEvent) : Line
    data class Sleep(val e: SleepEvent) : Line
    data class Diaper(val e: DiaperEvent) : Line
    data class Tummy(val e: TummyTimeEvent) : Line
}

private data class HistorySummary(
    val feedingNow: Boolean,
    val sleepingNow: Boolean,
    val lastFeedAgoMs: Long?,
    val lastSleepAgoMs: Long?,
    val nextFeedInMs: Long?,
    val lastCryAgoMs: Long?,
    val lastCryReason: CryReason?,
    val criesToday: Int,
    val criesYesterday: Int,
    val feedsToday: Int,
    val sleepsToday: Int,
    val diapersToday: Int,
    val poopsToday: Int,
    val tummyToday: Int,
    val tummyGoal: Int,
    val topReasonToday: CryReason?,
    val peakHour: Int?,
    val avgFeedGapMs: Long?,
    val perDay: List<Pair<String, Int>>,
    val diaperPerDay: List<Pair<String, Int>>,
    val tummyPerDay: List<Pair<String, Int>>,
    val perHour: List<Int>,     // 24 slots, cries started in each hour-of-day
)

private fun reasonOf(e: CryEvent, labels: List<CryReason>): CryReason? {
    val idx = e.confirmedIndex?.takeIf { it in labels.indices } ?: e.predictedIndex
    return idx.takeIf { it in labels.indices }?.let { labels[it] }
}

private fun computeSummary(
    cries: List<CryEvent>,
    feedings: List<FeedingEvent>,
    sleeps: List<SleepEvent>,
    diapers: List<DiaperEvent>,
    tummy: List<TummyTimeEvent>,
    labels: List<CryReason>,
    profile: BabyProfile,
    now: Long,
): HistorySummary {
    val todayStart = startOfDay(now)
    val todayEnd = nextDayStart(todayStart)
    val yesterdayStart = previousDayStart(todayStart)

    // A currently running feed should not reset the hunger clock until it actually ends. While
    // one is running we treat the baby as "just fed" (no next-feed countdown yet).
    val feedingNow = feedings.any { it.durationMs < 0L }
    val completedFeedings = feedings.filter { it.durationMs >= 0L }
    // Order by end time (start + duration), so an edited session that started earlier but ended
    // later still counts as the most recent feed.
    val lastFeed = if (feedingNow) null else completedFeedings.maxOfOrNull(::feedingEndedAt)
    val expectedHours = ContextPrior.expectedFeedIntervalHours(profile.ageMonths(now), profile.ageDays(now))
    val nextFeedInMs = lastFeed?.let { it + (expectedHours * 3_600_000L).toLong() - now }

    val sleepingNow = sleeps.any { it.durationMs < 0L }
    val completedSleeps = sleeps.filter { it.durationMs >= 0L }
    val lastSleep = if (sleepingNow) null else completedSleeps.maxOfOrNull(::sleepEndedAt)

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

    val feedEndsDesc = completedFeedings.map(::feedingEndedAt).sortedDescending()
    val avgFeedGapMs = if (feedEndsDesc.size >= 2) {
        var sum = 0L
        for (i in 0 until feedEndsDesc.size - 1) {
            sum += feedEndsDesc[i] - feedEndsDesc[i + 1]
        }
        sum / (feedEndsDesc.size - 1)
    } else null

    fun dayRange(back: Int): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            timeInMillis = todayStart
            add(Calendar.DATE, -back)
        }.timeInMillis
        return start to nextDayStart(start)
    }

    val perDay = (6 downTo 0).map { back ->
        val (start, end) = dayRange(back)
        dayShortFormat().format(Date(start)) to cries.count { it.timestamp in start until end }
    }
    val diaperPerDay = (6 downTo 0).map { back ->
        val (start, end) = dayRange(back)
        dayShortFormat().format(Date(start)) to diapers.count { it.timestamp in start until end }
    }
    val tummyPerDay = (6 downTo 0).map { back ->
        val (start, end) = dayRange(back)
        dayShortFormat().format(Date(start)) to tummy.count { it.timestamp in start until end }
    }

    return HistorySummary(
        feedingNow = feedingNow,
        sleepingNow = sleepingNow,
        lastFeedAgoMs = lastFeed?.let { now - it },
        lastSleepAgoMs = lastSleep?.let { now - it },
        nextFeedInMs = nextFeedInMs,
        lastCryAgoMs = lastCry?.let { now - it.timestamp },
        lastCryReason = lastCry?.let { reasonOf(it, labels) },
        criesToday = cries.count { it.timestamp >= todayStart },
        criesYesterday = cries.count { it.timestamp in yesterdayStart until todayStart },
        feedsToday = feedings.count { sessionOverlapsDay(it.timestamp, it.durationMs, todayStart, todayEnd, now) },
        sleepsToday = sleeps.count { sessionOverlapsDay(it.timestamp, it.durationMs, todayStart, todayEnd, now) },
        diapersToday = diapers.count { it.timestamp >= todayStart },
        poopsToday = diapers.count {
            it.timestamp >= todayStart && DiaperType.fromNameOrNull(it.type)?.hasStool == true
        },
        tummyToday = tummy.count { it.timestamp >= todayStart },
        tummyGoal = TummyTime.dailyGoal(profile.ageDays(now)),
        topReasonToday = topReasonToday,
        peakHour = peakHour,
        avgFeedGapMs = avgFeedGapMs,
        perDay = perDay,
        diaperPerDay = diaperPerDay,
        tummyPerDay = tummyPerDay,
        perHour = perHour.toList(),
    )
}

private fun computeDaySummary(
    cries: List<CryEvent>,
    feedings: List<FeedingEvent>,
    sleeps: List<SleepEvent>,
    diapers: List<DiaperEvent>,
    tummy: List<TummyTimeEvent>,
    labels: List<CryReason>,
    profile: BabyProfile,
    dayStart: Long,
    now: Long = System.currentTimeMillis(),
): DaySummary {
    val dayEnd = nextDayStart(dayStart)
    val prevStart = previousDayStart(dayStart)
    fun inDay(ts: Long) = ts in dayStart until dayEnd

    val dayCounts = IntArray(labels.size)
    for (e in cries) if (inDay(e.timestamp)) {
        reasonOf(e, labels)?.let { dayCounts[it.ordinal]++ }
    }
    var topIdx = -1
    for (i in dayCounts.indices) {
        if (dayCounts[i] > 0 && (topIdx < 0 || dayCounts[i] > dayCounts[topIdx])) topIdx = i
    }
    val topReason = topIdx.takeIf { it >= 0 }?.let { labels[it] }

    val criesCount = cries.count { inDay(it.timestamp) }
    val prevCries = cries.count { it.timestamp in prevStart until dayStart }
    val criesComparisonText = if (criesCount > 0 || prevCries > 0) {
        criesComparisonForDay(criesCount - prevCries, prevCries)
    } else {
        null
    }

    return DaySummary(
        cries = criesCount,
        feeds = feedings.count { sessionOverlapsDay(it.timestamp, it.durationMs, dayStart, dayEnd, now) },
        sleeps = sleeps.count { sessionOverlapsDay(it.timestamp, it.durationMs, dayStart, dayEnd, now) },
        diapers = diapers.count { inDay(it.timestamp) },
        poops = diapers.count {
            inDay(it.timestamp) && DiaperType.fromNameOrNull(it.type)?.hasStool == true
        },
        tummy = tummy.count { inDay(it.timestamp) },
        tummyGoal = TummyTime.dailyGoal(profile.ageDays(dayStart + (dayEnd - dayStart) / 2)),
        topReason = topReason,
        criesYesterdayComparison = criesComparisonText,
    )
}

private fun collectLoggedDays(
    cries: List<CryEvent>,
    feedings: List<FeedingEvent>,
    sleeps: List<SleepEvent>,
    diapers: List<DiaperEvent>,
    tummy: List<TummyTimeEvent>,
    weights: List<WeightEvent>,
    heights: List<HeightEvent>,
): Set<Long> {
    val days = mutableSetOf<Long>()
    cries.forEach { days += startOfDay(it.timestamp) }
    feedings.forEach { addSessionLoggedDays(days, it.timestamp, it.durationMs) }
    sleeps.forEach { addSessionLoggedDays(days, it.timestamp, it.durationMs) }
    diapers.forEach { days += startOfDay(it.timestamp) }
    tummy.forEach { days += startOfDay(it.timestamp) }
    weights.forEach { days += startOfDay(it.timestamp) }
    heights.forEach { days += startOfDay(it.timestamp) }
    return days
}

/** Marks every local day a completed/running session overlaps. */
private fun addSessionLoggedDays(days: MutableSet<Long>, start: Long, durationMs: Long) {
    days += startOfDay(start)
    if (durationMs > 0L) {
        days += startOfDay(start + durationMs)
    }
}

private fun feedingEndedAt(feeding: FeedingEvent): Long =
    feeding.timestamp + feeding.durationMs.coerceAtLeast(0L)

private fun sleepEndedAt(sleep: SleepEvent): Long =
    sleep.timestamp + sleep.durationMs.coerceAtLeast(0L)

/** Inclusive overlap: session touches any part of [dayStart, dayEnd). */
private fun sessionOverlapsDay(
    start: Long,
    durationMs: Long,
    dayStart: Long,
    dayEnd: Long,
    now: Long,
): Boolean {
    val end = if (durationMs < 0L) now else start + durationMs
    return start < dayEnd && end > dayStart
}

/**
 * Clock label for a session on [dayStart]'s timeline. Overnight sessions that started
 * yesterday show today's end (or "now" if still running), not yesterday's start time.
 */
private fun displayTimeOnDay(
    start: Long,
    durationMs: Long,
    dayStart: Long,
    now: Long = System.currentTimeMillis(),
): Long {
    if (start >= dayStart) return start
    val end = if (durationMs < 0L) now else start + durationMs
    return end.coerceAtLeast(dayStart)
}

private fun buildDayTimeline(
    cries: List<CryEvent>,
    feedings: List<FeedingEvent>,
    sleeps: List<SleepEvent>,
    diapers: List<DiaperEvent>,
    tummy: List<TummyTimeEvent>,
    dayStart: Long,
    now: Long = System.currentTimeMillis(),
): List<Line> {
    val dayEnd = nextDayStart(dayStart)
    fun inDay(ts: Long) = ts in dayStart until dayEnd

    val entries = ArrayList<Pair<Long, Line>>()
    cries.filter { inDay(it.timestamp) }.forEach { entries += it.timestamp to Line.Cry(it) }
    feedings.filter { sessionOverlapsDay(it.timestamp, it.durationMs, dayStart, dayEnd, now) }
        .forEach { entries += displayTimeOnDay(it.timestamp, it.durationMs, dayStart, now) to Line.Feed(it) }
    sleeps.filter { sessionOverlapsDay(it.timestamp, it.durationMs, dayStart, dayEnd, now) }
        .forEach { entries += displayTimeOnDay(it.timestamp, it.durationMs, dayStart, now) to Line.Sleep(it) }
    diapers.filter { inDay(it.timestamp) }.forEach { entries += it.timestamp to Line.Diaper(it) }
    tummy.filter { inDay(it.timestamp) }.forEach { entries += it.timestamp to Line.Tummy(it) }
    // Read the day as it happened: earliest care event first, then the next one.
    entries.sortBy { it.first }
    return entries.map { it.second }
}

private fun dayLabel(dayStart: Long, todayStart: Long): String = when (dayStart) {
    todayStart -> translate(currentAppLang, "Σήμερα")
    previousDayStart(todayStart) -> translate(currentAppLang, "Χθες")
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

/** Calendar-safe previous local midnight (handles DST 23h/25h days). */
private fun previousDayStart(dayStart: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = dayStart }
    c.add(Calendar.DATE, -1)
    return c.timeInMillis
}

/** Calendar-safe next local midnight (handles DST 23h/25h days). */
private fun nextDayStart(dayStart: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = dayStart }
    c.add(Calendar.DATE, 1)
    return c.timeInMillis
}

/**
 * When end clock is earlier than start on the same calendar day label, treat end as next
 * local midnight + end-of-day offset (DST-safe day length).
 */
private fun durationCrossingMidnight(startedAt: Long, endOnStartDay: Long): Long {
    val delta = endOnStartDay - startedAt
    if (delta > 0L) return delta
    if (delta == 0L) return 0L
    val dayStart = startOfDay(startedAt)
    val dayLength = nextDayStart(dayStart) - dayStart
    return delta + dayLength
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
