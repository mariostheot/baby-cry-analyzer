package com.babycry.analyzer.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babycry.analyzer.growth.WhoGrowthCurves
import com.babycry.analyzer.growth.WhoGrowthCurvesLoader
import com.babycry.analyzer.growth.WhoGrowthPoint
import com.babycry.analyzer.model.BabyGender
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * An informational reference screen (like Tummy Time): it shows the WHO 0–5 year weight- and
 * height-for-age reference curves for a chosen sex. It intentionally does NOT plot the baby's own
 * measurements — those live in the History screen.
 */
@Composable
fun GrowthScreen(
    viewModel: CryViewModel,
    modifier: Modifier = Modifier,
) {
    val profile by viewModel.profile.collectAsState()
    val context = LocalContext.current

    // Default to the active baby's sex if we know it, otherwise show boys' curves first.
    var selectedSex by remember(profile.id) {
        mutableStateOf(if (profile.gender == BabyGender.GIRL) BabyGender.GIRL else BabyGender.BOY)
    }

    // Assets are read off the main thread; the loader also caches per sex.
    val load by produceState<GrowthLoad>(initialValue = GrowthLoad.Loading, selectedSex) {
        value = GrowthLoad.Loading
        val loaded = withContext(Dispatchers.IO) { WhoGrowthCurvesLoader.load(context, selectedSex) }
        value = if (loaded != null) GrowthLoad.Ready(loaded) else GrowthLoad.Failed
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            tr("Καμπύλες ανάπτυξης"),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Text(
            tr("Καμπύλες αναφοράς βάρους και ύψους ανά ηλικία και φύλο, από τον Παγκόσμιο Οργανισμό Υγείας (ΠΟΥ). Ενημερωτικό βοήθημα — δεν χρησιμοποιεί τα δικά σου δεδομένα."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedSex == BabyGender.BOY,
                onClick = { selectedSex = BabyGender.BOY },
                label = { Text(tr("Αγόρι")) },
            )
            FilterChip(
                selected = selectedSex == BabyGender.GIRL,
                onClick = { selectedSex = BabyGender.GIRL },
                label = { Text(tr("Κορίτσι")) },
            )
        }
        Spacer(Modifier.height(16.dp))

        when (val current = load) {
            GrowthLoad.Loading -> Text(
                tr("Φόρτωση..."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            GrowthLoad.Failed -> GateMessage(tr("Δεν φορτώθηκαν τα δεδομένα WHO. Δοκίμασε ξανά αργότερα."))
            is GrowthLoad.Ready -> {
                GrowthChartCard(
                    title = tr("Βάρος για την ηλικία"),
                    yUnitLabel = tr("kg"),
                    reference = current.curves.weight,
                    valueFromPoint = { it / 1000f },
                    formatY = { formatKg(it) },
                )
                Spacer(Modifier.height(16.dp))
                GrowthChartCard(
                    title = tr("Μήκος/ύψος για την ηλικία"),
                    yUnitLabel = tr("cm"),
                    reference = current.curves.height,
                    valueFromPoint = { it / 10f },
                    formatY = { formatCm(it) },
                )

                Spacer(Modifier.height(16.dp))
                DisclaimerCard()
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Πηγή δεδομένων: WHO Child Growth Standards, 2006. Χωρίς έγκριση ή σύνδεση με τον WHO."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private sealed interface GrowthLoad {
    data object Loading : GrowthLoad
    data object Failed : GrowthLoad
    data class Ready(val curves: WhoGrowthCurves) : GrowthLoad
}

@Composable
private fun GateMessage(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun DisclaimerCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            tr("Οι καμπύλες είναι σημείο αναφοράς του WHO και όχι ιατρική διάγνωση. Συζήτησε ανησυχίες με παιδίατρο."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GrowthChartCard(
    title: String,
    yUnitLabel: String,
    reference: List<WhoGrowthPoint>,
    valueFromPoint: (Int) -> Float,
    formatY: (Float) -> String,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            // FlowRow so the (long, translated) legend labels wrap onto new lines instead of being
            // squeezed to zero width — which previously forced the whole legend to grow very tall.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LegendDot(tr("Κάτω γραμμή αναφοράς WHO"), MaterialTheme.colorScheme.outline)
                LegendDot(tr("Μέση γραμμή αναφοράς WHO"), MaterialTheme.colorScheme.outline.copy(alpha = 0.85f))
                LegendDot(tr("Πάνω γραμμή αναφοράς WHO"), MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
            }
            Spacer(Modifier.height(8.dp))
            WhoReferenceChart(
                reference = reference,
                valueFromPoint = valueFromPoint,
                yUnitLabel = yUnitLabel,
                formatY = formatY,
            )
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp)) {
            drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(size.width / 2, size.height / 2))
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

private const val MIN_WINDOW_MONTHS = 3f

@Composable
private fun WhoReferenceChart(
    reference: List<WhoGrowthPoint>,
    valueFromPoint: (Int) -> Float,
    yUnitLabel: String,
    formatY: (Float) -> String,
) {
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val outline = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    val refLower = reference.map { valueFromPoint(it.lower) }
    val refMed = reference.map { valueFromPoint(it.median) }
    val refUpper = reference.map { valueFromPoint(it.upper) }
    val lastMonth = (reference.size - 1).coerceAtLeast(1).toFloat()

    // Visible age window in months; pinch/buttons change it. Reset when the dataset changes.
    var viewStart by remember(reference) { mutableFloatStateOf(0f) }
    var viewEnd by remember(reference) { mutableFloatStateOf(lastMonth) }

    fun clampWindow(start: Float, end: Float) {
        var s = start
        var e = end
        val win = (e - s).coerceIn(MIN_WINDOW_MONTHS, lastMonth)
        e = s + win
        if (s < 0f) { e -= s; s = 0f }
        if (e > lastMonth) { s -= (e - lastMonth); e = lastMonth }
        viewStart = s.coerceIn(0f, lastMonth)
        viewEnd = e.coerceIn(0f, lastMonth)
    }

    fun zoomAroundCenter(factor: Float) {
        val center = (viewStart + viewEnd) / 2f
        val newWin = ((viewEnd - viewStart) * factor).coerceIn(MIN_WINDOW_MONTHS, lastMonth)
        clampWindow(center - newWin / 2f, center + newWin / 2f)
    }

    // pointerInput captures composition values; keep a live view of the zoom window so pinch/pan
    // after the first gesture still uses the current range (not the initial 0…60 months).
    val viewStartLatest = rememberUpdatedState(viewStart)
    val viewEndLatest = rememberUpdatedState(viewEnd)
    val lastMonthLatest = rememberUpdatedState(lastMonth)
    val clampWindowLatest = rememberUpdatedState<(Float, Float) -> Unit> { s, e -> clampWindow(s, e) }

    // Auto-fit the vertical scale to the reference band *within the visible age window*, so
    // zooming into early months reveals fine detail (and gives the y-axis meaningful numbers).
    val loI = floor(viewStart).toInt().coerceIn(0, reference.size - 1)
    val hiI = ceil(viewEnd).toInt().coerceIn(0, reference.size - 1)
    val yMinRaw = (loI..hiI).minOf { refLower[it] }
    val yMaxRaw = (loI..hiI).maxOf { refUpper[it] }
    val yPad = ((yMaxRaw - yMinRaw) * 0.08f).coerceAtLeast(0.01f)
    val yMin = (yMinRaw - yPad).coerceAtLeast(0f)
    val yMax = yMaxRaw + yPad

    val yTicks = niceTicks(yMin, yMax, 6)
    val xTicks = monthTicks(viewStart, viewEnd)

    val labelPaint = remember {
        Paint().apply { isAntiAlias = true }
    }

    Column {
        // Zoom controls; also usable by anyone who can't comfortably pinch.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${floor(viewStart).toInt()}–${ceil(viewEnd).toInt()} " + tr("μήνες"),
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { zoomAroundCenter(1.6f) }) {
                Icon(Icons.Filled.Remove, contentDescription = tr("Σμίκρυνση"))
            }
            IconButton(onClick = { zoomAroundCenter(0.6f) }) {
                Icon(Icons.Filled.Add, contentDescription = tr("Μεγέθυνση"))
            }
            IconButton(onClick = { clampWindow(0f, lastMonth) }) {
                Icon(Icons.Filled.Refresh, contentDescription = tr("Επαναφορά"))
            }
        }

        val leftInset = 46.dp
        val bottomInset = 22.dp
        val topInset = 10.dp
        val rightInset = 10.dp

        Box(Modifier.fillMaxWidth().height(240.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(reference) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val plotLeft = leftInset.toPx()
                            val plotW = (size.width - plotLeft - rightInset.toPx()).coerceAtLeast(1f)
                            val start = viewStartLatest.value
                            val end = viewEndLatest.value
                            val maxMonth = lastMonthLatest.value
                            val win = end - start
                            val focalFrac = ((centroid.x - plotLeft) / plotW).coerceIn(0f, 1f)
                            val focalMonth = start + focalFrac * win
                            val newWin = (win / zoom).coerceIn(MIN_WINDOW_MONTHS, maxMonth)
                            val panMonths = -(pan.x / plotW) * newWin
                            val newStart = focalMonth - focalFrac * newWin + panMonths
                            clampWindowLatest.value(newStart, newStart + newWin)
                        }
                    },
            ) {
                val plotLeft = leftInset.toPx()
                val plotTop = topInset.toPx()
                val plotRight = size.width - rightInset.toPx()
                val plotBottom = size.height - bottomInset.toPx()
                val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
                val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
                val win = (viewEnd - viewStart).coerceAtLeast(0.01f)
                val yRange = (yMax - yMin).coerceAtLeast(0.01f)

                fun xFor(month: Float): Float = plotLeft + ((month - viewStart) / win) * plotW
                fun yFor(v: Float): Float = plotBottom - ((v - yMin) / yRange) * plotH

                labelPaint.color = labelColor.toArgb()
                labelPaint.textSize = 11.sp.toPx()

                // Horizontal grid + y-axis value labels.
                labelPaint.textAlign = Paint.Align.RIGHT
                val fm = labelPaint.fontMetrics
                yTicks.forEach { v ->
                    val y = yFor(v)
                    drawLine(grid, Offset(plotLeft, y), Offset(plotRight, y), 1.dp.toPx())
                    drawContext.canvas.nativeCanvas.drawText(
                        formatY(v),
                        plotLeft - 4.dp.toPx(),
                        y - (fm.ascent + fm.descent) / 2f,
                        labelPaint,
                    )
                }

                // Vertical grid + x-axis (age) labels.
                labelPaint.textAlign = Paint.Align.CENTER
                xTicks.forEach { m ->
                    val x = xFor(m)
                    drawLine(grid, Offset(x, plotTop), Offset(x, plotBottom), 1.dp.toPx())
                    drawContext.canvas.nativeCanvas.drawText(
                        "${m.toInt()}",
                        x,
                        plotBottom + 15.dp.toPx(),
                        labelPaint,
                    )
                }

                // Unit caption at the top-left of the plot.
                labelPaint.textAlign = Paint.Align.LEFT
                drawContext.canvas.nativeCanvas.drawText(
                    yUnitLabel,
                    plotLeft + 2.dp.toPx(),
                    plotTop + 10.dp.toPx(),
                    labelPaint,
                )

                fun drawCurve(values: List<Float>, color: Color, stroke: Float) {
                    val path = Path()
                    var started = false
                    values.forEachIndexed { idx, v ->
                        val x = xFor(idx.toFloat())
                        val y = yFor(v)
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(width = stroke))
                }

                clipRect(plotLeft, plotTop, plotRight, plotBottom) {
                    drawCurve(refLower, outline.copy(alpha = 0.5f), 1.5.dp.toPx())
                    drawCurve(refMed, outline.copy(alpha = 0.9f), 2.5.dp.toPx())
                    drawCurve(refUpper, outline.copy(alpha = 0.5f), 1.5.dp.toPx())
                }
            }
        }
        Text(
            tr("Ηλικία (μήνες) · κάνε pinch ή +/− για μεγέθυνση"),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.padding(start = 46.dp, top = 2.dp),
        )
    }
}

/** Evenly spaced "nice" tick values (1/2/5 × 10ⁿ) covering [min, max]. */
private fun niceTicks(min: Float, max: Float, target: Int): List<Float> {
    if (max <= min || target < 2) return listOf(min, max)
    val step = niceNum((max - min) / (target - 1), round = true)
    val start = ceil(min / step) * step
    val ticks = ArrayList<Float>()
    var v = start
    while (v <= max + step * 0.001f) {
        ticks.add(v)
        v += step
    }
    return if (ticks.size >= 2) ticks else listOf(min, max)
}

private fun niceNum(range: Float, round: Boolean): Float {
    val exp = floor(log10(range.toDouble())).toInt()
    val frac = range / 10.0.pow(exp).toFloat()
    val niceFrac = if (round) {
        when {
            frac < 1.5f -> 1f
            frac < 3f -> 2f
            frac < 7f -> 5f
            else -> 10f
        }
    } else {
        when {
            frac <= 1f -> 1f
            frac <= 2f -> 2f
            frac <= 5f -> 5f
            else -> 10f
        }
    }
    return niceFrac * 10.0.pow(exp).toFloat()
}

/** Whole-month tick marks for the visible window, aiming for ~6 evenly spaced labels. */
private fun monthTicks(start: Float, end: Float): List<Float> {
    val win = (end - start).coerceAtLeast(1f)
    val step = listOf(1f, 2f, 3f, 6f, 12f).firstOrNull { it >= win / 6f } ?: 12f
    val first = ceil(start / step) * step
    val ticks = ArrayList<Float>()
    var m = first
    while (m <= end + 0.001f) {
        ticks.add(m)
        m += step
    }
    return if (ticks.isEmpty()) listOf(ceil(start), floor(end)) else ticks
}

private fun formatKg(v: Float): String {
    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    return String.format(locale, "%.1f", v)
}

private fun formatCm(v: Float): String {
    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    return String.format(locale, "%.0f", v)
}
