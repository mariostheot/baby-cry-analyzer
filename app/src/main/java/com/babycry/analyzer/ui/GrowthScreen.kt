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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.data.HeightEvent
import com.babycry.analyzer.data.WeightEvent
import com.babycry.analyzer.growth.WhoGrowthCurvesLoader
import com.babycry.analyzer.growth.WhoGrowthMath
import com.babycry.analyzer.growth.WhoGrowthPoint
import com.babycry.analyzer.model.BabyGender
import com.babycry.analyzer.model.BabyProfile
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.tr
import java.util.Locale

private data class PlottedPoint(val ageMonths: Double, val value: Float)

@Composable
fun GrowthScreen(
    viewModel: CryViewModel,
    modifier: Modifier = Modifier,
) {
    val profile by viewModel.profile.collectAsState()
    val weights by viewModel.recentWeights.collectAsState()
    val heights by viewModel.recentHeights.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            tr("WHO καμπύλες ανάπτυξης"),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )

        val birth = profile.birthMillis

        when {
            birth == null -> GateMessage(tr("Για τις καμπύλες WHO χρειάζεται η ημερομηνία γέννησης. Όρισέ την στα «Στοιχεία ενεργού μωρού» στις Ρυθμίσεις και πάτα «Πίσω»."))
            profile.gender == BabyGender.UNKNOWN -> GateMessage(tr("Για τις καμπύλες WHO χρειάζεται το φύλο (αγόρι ή κορίτσι). Όρισέ το στα «Στοιχεία ενεργού μωρού» στις Ρυθμίσεις και πάτα «Πίσω»."))
            else -> {
                val curves = remember(profile.gender) {
                    WhoGrowthCurvesLoader.load(context, profile.gender)
                }
                if (curves == null) {
                    GateMessage(tr("Δεν φορτώθηκαν τα δεδομένα WHO. Δοκίμασε ξανά αργότερα."))
                } else {
                    Text(
                        tr("Οι γραμμές δείχνουν τις επίσημες καμπύλες αναφοράς WHO (−2 SD, διάμεσος, +2 SD). Τα σημεία είναι οι δικές σου καταγραφές βάρους/ύψους."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(16.dp))

                    val weightPlot = plotWeightPoints(profile, weights)
                    val heightPlot = plotHeightPoints(profile, heights)
                    val weightSkipped = weights.isNotEmpty() && weightPlot.isEmpty()
                    val heightSkipped = heights.isNotEmpty() && heightPlot.isEmpty()

                    if (weightSkipped || heightSkipped) {
                        Text(
                            tr("Κάποιες καταγραφές δεν εμφανίζονται: μόνο μετρήσεις από τη γέννηση έως 60 μηνών ηλικίας."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    GrowthChartCard(
                        title = tr("Βάρος για την ηλικία"),
                        yUnitLabel = tr("kg"),
                        reference = curves.weight,
                        valueFromPoint = { it / 1000f },
                        userPoints = weightPlot,
                        formatY = { formatKg(it) },
                        noDataCaption = tr("Δεν υπάρχουν καταγραφές βάρους ακόμα."),
                    )
                    Spacer(Modifier.height(16.dp))
                    GrowthChartCard(
                        title = tr("Μήκος/ύψος για την ηλικία"),
                        yUnitLabel = tr("cm"),
                        reference = curves.height,
                        valueFromPoint = { it / 10f },
                        userPoints = heightPlot,
                        formatY = { formatCm(it) },
                        noDataCaption = tr("Δεν υπάρχουν καταγραφές ύψους ακόμα."),
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
        }
    }
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

private fun plotWeightPoints(profile: BabyProfile, events: List<WeightEvent>): List<PlottedPoint> {
    val birth = profile.birthMillis ?: return emptyList()
    return events.mapNotNull { e ->
        val age = WhoGrowthMath.ageMonthsForPlot(birth, e.timestamp) ?: return@mapNotNull null
        PlottedPoint(age, e.grams / 1000f)
    }.sortedBy { it.ageMonths }
}

private fun plotHeightPoints(profile: BabyProfile, events: List<HeightEvent>): List<PlottedPoint> {
    val birth = profile.birthMillis ?: return emptyList()
    return events.mapNotNull { e ->
        val age = WhoGrowthMath.ageMonthsForPlot(birth, e.timestamp) ?: return@mapNotNull null
        PlottedPoint(age, e.millimeters / 10f)
    }.sortedBy { it.ageMonths }
}

@Composable
private fun GrowthChartCard(
    title: String,
    yUnitLabel: String,
    reference: List<WhoGrowthPoint>,
    valueFromPoint: (Int) -> Float,
    userPoints: List<PlottedPoint>,
    formatY: (Float) -> String,
    noDataCaption: String,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot(tr("WHO −2 SD"), MaterialTheme.colorScheme.outline)
                LegendDot(tr("WHO διάμεσος"), MaterialTheme.colorScheme.outline.copy(alpha = 0.85f))
                LegendDot(tr("WHO +2 SD"), MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
                LegendDot(tr("Οι καταγραφές σου"), MaterialTheme.colorScheme.primary)
            }
            if (userPoints.isEmpty()) {
                Text(
                    noDataCaption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            WhoReferenceChart(
                reference = reference,
                valueFromPoint = valueFromPoint,
                userPoints = userPoints,
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
    userPoints: List<PlottedPoint>,
    yUnitLabel: String,
    formatY: (Float) -> String,
) {
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    val refLower = reference.map { valueFromPoint(it.lower) }
    val refMed = reference.map { valueFromPoint(it.median) }
    val refUpper = reference.map { valueFromPoint(it.upper) }
    val allY = refLower + refMed + refUpper + userPoints.map { it.value }
    val yMin = (allY.minOrNull() ?: 0f) * 0.95f
    val yMax = (allY.maxOrNull() ?: 1f) * 1.05f
    val yRange = (yMax - yMin).coerceAtLeast(0.1f)

    Column {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            Text(
                "${formatY(yMax)} $yUnitLabel",
                style = labelStyle,
                color = labelColor,
                modifier = Modifier.align(Alignment.TopStart),
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
                drawCurve(refMed, outline.copy(alpha = 0.75f), 2.dp.toPx())
                drawCurve(refUpper, outline.copy(alpha = 0.45f), 1.5.dp.toPx())

                if (userPoints.size == 1) {
                    val p = userPoints.first()
                    drawCircle(
                        color = primary,
                        radius = 5.dp.toPx(),
                        center = Offset(xFor(p.ageMonths.toFloat()), yFor(p.value)),
                    )
                } else if (userPoints.size > 1) {
                    for (i in 0 until userPoints.lastIndex) {
                        val a = userPoints[i]
                        val b = userPoints[i + 1]
                        drawLine(
                            color = primary,
                            start = Offset(xFor(a.ageMonths.toFloat()), yFor(a.value)),
                            end = Offset(xFor(b.ageMonths.toFloat()), yFor(b.value)),
                            strokeWidth = 2.5.dp.toPx(),
                        )
                    }
                    userPoints.forEach { p ->
                        drawCircle(
                            color = primary,
                            radius = 4.dp.toPx(),
                            center = Offset(xFor(p.ageMonths.toFloat()), yFor(p.value)),
                        )
                    }
                }
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
    return String.format(locale, "%.2f", v)
}

private fun formatCm(v: Float): String {
    val locale = if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el")
    return String.format(locale, "%.1f", v)
}
