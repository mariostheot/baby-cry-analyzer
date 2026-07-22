package com.babycry.analyzer.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun WhoReferenceChart(
    reference: List<WhoGrowthPoint>,
    valueFromPoint: (Int) -> Float,
    yUnitLabel: String,
    formatY: (Float) -> String,
) {
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val outline = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    val refLower = reference.map { valueFromPoint(it.lower) }
    val refMed = reference.map { valueFromPoint(it.median) }
    val refUpper = reference.map { valueFromPoint(it.upper) }
    // Scale strictly to the reference band so the curves fill the plot (no empty headroom).
    val yMinRaw = refLower.minOrNull() ?: 0f
    val yMaxRaw = refUpper.maxOrNull() ?: 1f
    val pad = ((yMaxRaw - yMinRaw) * 0.05f).coerceAtLeast(0.01f)
    val yMin = (yMinRaw - pad).coerceAtLeast(0f)
    val yMax = yMaxRaw + pad
    val yRange = (yMax - yMin).coerceAtLeast(0.1f)
    val yMid = (yMin + yMax) / 2f

    Column {
        Box(Modifier.fillMaxWidth().height(220.dp)) {
            Text(
                "${formatY(yMax)} $yUnitLabel",
                style = labelStyle,
                color = labelColor,
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                formatY(yMid),
                style = labelStyle,
                color = labelColor,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                "${formatY(yMin)} $yUnitLabel",
                style = labelStyle,
                color = labelColor,
                modifier = Modifier.align(Alignment.BottomStart),
            )
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 48.dp, top = 12.dp, bottom = 20.dp, end = 8.dp),
            ) {
                val w = size.width
                val h = size.height
                fun xFor(month: Float): Float = (month / 60f) * w
                fun yFor(v: Float): Float = h - ((v - yMin) / yRange) * h

                // Light vertical guides every 12 months so ages are easy to read across.
                listOf(0, 12, 24, 36, 48, 60).forEach { m ->
                    val x = xFor(m.toFloat())
                    drawLine(
                        color = grid,
                        start = Offset(x, 0f),
                        end = Offset(x, h),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                // Horizontal mid guide.
                drawLine(
                    color = grid,
                    start = Offset(0f, h / 2f),
                    end = Offset(w, h / 2f),
                    strokeWidth = 1.dp.toPx(),
                )

                fun drawCurve(values: List<Float>, color: Color, stroke: Float) {
                    if (values.isEmpty()) return
                    val path = Path()
                    values.forEachIndexed { idx, v ->
                        val x = xFor(idx.toFloat())
                        val y = yFor(v)
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(width = stroke))
                }

                drawCurve(refLower, outline.copy(alpha = 0.45f), 1.5.dp.toPx())
                drawCurve(refMed, outline.copy(alpha = 0.85f), 2.5.dp.toPx())
                drawCurve(refUpper, outline.copy(alpha = 0.45f), 1.5.dp.toPx())
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf(0, 12, 24, 36, 48, 60).forEach { m ->
                Text("$m", style = labelStyle, color = labelColor)
            }
        }
        Text(
            tr("Ηλικία (μήνες)"),
            style = labelStyle,
            color = labelColor,
            modifier = Modifier.padding(start = 48.dp, top = 2.dp),
        )
    }
}

private fun formatKg(v: Float): String {
    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    return String.format(locale, "%.1f", v)
}

private fun formatCm(v: Float): String {
    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    return String.format(locale, "%.0f", v)
}
