package com.babycry.analyzer.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.data.HeightEvent
import com.babycry.analyzer.data.WeightEvent
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WeightHistoryCard(
    weights: List<WeightEvent>,
    onEdit: (WeightEvent) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(tr("Βάρος"), style = MaterialTheme.typography.titleMedium)
            if (weights.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                MeasurementHint(
                    tr("Δεν έχει καταγραφεί βάρος ακόμη. Πρόσθεσέ το από την Αρχική μετά από επίσκεψη στον παιδίατρο."),
                )
            } else {
                Spacer(Modifier.height(8.dp))
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
fun HeightHistoryCard(
    heights: List<HeightEvent>,
    onEdit: (HeightEvent) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(tr("Ύψος"), style = MaterialTheme.typography.titleMedium)
            if (heights.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                MeasurementHint(
                    tr("Δεν έχει καταγραφεί ύψος ακόμη. Πρόσθεσέ το από την Αρχική μετά από επίσκεψη στον παιδίατρο."),
                )
            } else {
                Spacer(Modifier.height(8.dp))
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
fun WeightEntryDialog(
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
        ?.takeIf { value -> kotlin.math.round(value * 1000).toInt() in 1..30_000 }
    val timestampValid = timestampMillis <= System.currentTimeMillis()
    val valid = parsedKg != null && timestampValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (initial == null) "Προσθήκη βάρους" else "Επεξεργασία βάρους")) },
        text = {
            Column {
                OutlinedTextField(
                    value = kgText,
                    onValueChange = { kgText = sanitizeDecimalMeasurementInput(it) },
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
fun HeightEntryDialog(
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
                    onValueChange = { cmText = sanitizeDecimalMeasurementInput(it) },
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
private fun formatWeightKg(grams: Int): String {
    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    return String.format(locale, "%.2f kg", grams / 1000.0)
}

@Composable
private fun formatHeightCm(millimeters: Int): String {
    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    return String.format(locale, "%.1f cm", millimeters / 10.0)
}

@Composable
private fun MeasurementHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}
