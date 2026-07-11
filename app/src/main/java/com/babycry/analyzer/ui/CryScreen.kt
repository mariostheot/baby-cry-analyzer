package com.babycry.analyzer.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babycry.analyzer.data.CryEvent
import com.babycry.analyzer.model.AnalysisEngine
import com.babycry.analyzer.ml.CryAnalysis
import com.babycry.analyzer.model.CryReason
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.tr
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    viewModel: CryViewModel,
    onListen: () -> Unit,
    onCancel: () -> Unit,
    onSoothe: () -> Unit,
    onSafety: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.home.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val pending by viewModel.pendingConfirmation.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tr("Άκου το μωρό"),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = tr(if (viewModel.hasModel) "AI μοντέλο ενεργό" else "Πρόχειρη εκτίμηση (χωρίς μοντέλο)"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (profile.hasName) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "👶 " + profile.name + babyAgeSuffix(profile),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Ask about the *previous* cry (once the parent has likely figured out the cause),
        // but not for the cry whose result is already on screen right now.
        val current = pending
        if (current != null && current.id != state.eventId) {
            Spacer(Modifier.height(20.dp))
            PendingConfirmCard(
                event = current,
                labels = viewModel.labels,
                babyName = profile.name,
                onConfirm = { viewModel.confirmPending(it) },
                onDismiss = { viewModel.dismissPending() },
            )
        }

        Spacer(Modifier.height(28.dp))
        ListenControl(phase = state.phase, level = state.level, onStart = onListen)
        Spacer(Modifier.height(16.dp))
        ListenStatus(phase = state.phase, level = state.level, onCancel = onCancel)

        Spacer(Modifier.height(24.dp))

        if (state.phase == Phase.RESULT && state.analysis != null) {
            ResultCard(
                analysis = state.analysis!!,
                feedbackGiven = state.feedbackGiven,
                feedbackDeferred = state.feedbackDeferred,
                canReplay = viewModel.canReplay,
                onReplay = { viewModel.playLastRecording() },
                onCorrect = { viewModel.confirmPredictionCorrect() },
                onDefer = { viewModel.deferFeedback() },
            )
            Spacer(Modifier.height(16.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { viewModel.logFeeding() }) {
                Icon(Icons.Filled.Restaurant, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(tr("Τάισμα"))
            }
            FilledTonalButton(onClick = onSoothe) {
                Icon(Icons.Filled.MusicNote, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(tr("Ηρέμησέ το"))
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSafety) { Text(tr("Πότε να ανησυχήσω;")) }

        Spacer(Modifier.height(8.dp))
        Text(
            text = tr("Ενημερωτικό βοήθημα, όχι ιατρική συμβουλή. Αν ανησυχείς για την υγεία του μωρού, ρώτησε παιδίατρο."),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

private fun babyAgeSuffix(profile: com.babycry.analyzer.model.BabyProfile): String {
    val (months, weeks) = profile.ageMonthsWeeks() ?: return ""
    return " · " + formatBabyAge(months, weeks)
}

/**
 * Friendly Greek age. Under a month → only weeks ("3 εβδομάδες"); under two years →
 * months (+ weeks when relevant, "1 μήνας και 2 εβδομάδες"); older → years.
 */
private fun formatBabyAge(months: Int, weeks: Int): String = when (currentAppLang) {
    AppLang.EN -> when {
        months >= 24 -> {
            val years = months / 12
            if (years == 1) "1 year old" else "$years years old"
        }
        months == 0 -> when {
            weeks == 0 -> "newborn"
            weeks == 1 -> "1 week"
            else -> "$weeks weeks"
        }
        months in 12..23 -> if (months == 1) "1 month old" else "$months months old"
        weeks == 0 -> if (months == 1) "1 month" else "$months months"
        else -> {
            val m = if (months == 1) "1 month" else "$months months"
            val w = if (weeks == 1) "1 week" else "$weeks weeks"
            "$m and $w"
        }
    }
    AppLang.EL -> {
        val monthWord = if (months == 1) "μήνας" else "μήνες"
        val weekWord = if (weeks == 1) "εβδομάδα" else "εβδομάδες"
        when {
            months >= 24 -> "${months / 12} χρονών"
            months == 0 -> if (weeks == 0) "νεογέννητο" else "$weeks $weekWord"
            months in 12..23 -> "$months μηνών"
            weeks == 0 -> "$months $monthWord"
            else -> "$months $monthWord και $weeks $weekWord"
        }
    }
}

@Composable
private fun ListenControl(phase: Phase, level: Float, onStart: () -> Unit) {
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        when (phase) {
            Phase.RECORDING -> ListeningRings(level)
            Phase.ANALYZING -> AnalyzingOrb()
            else -> StartOrb(onStart)
        }
    }
}

@Composable
private fun StartOrb(onStart: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(150.dp)
            .background(color.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .background(color, CircleShape)
                .clickable(onClick = onStart),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = tr("Ηχογράφηση"),
                tint = Color.White,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}

/**
 * Shazam-style: concentric ripples that keep expanding while we listen, plus a central orb
 * that gently pulses with the live mic level so it visibly reacts to sound.
 */
@Composable
private fun ListeningRings(level: Float) {
    val color = MaterialTheme.colorScheme.tertiary
    val transition = rememberInfiniteTransition(label = "rings")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )
    val pulse by animateFloatAsState(
        targetValue = 1f + level.coerceIn(0f, 1f) * 0.25f,
        label = "pulse",
    )

    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        for (i in 0 until 3) {
            val phase = (t + i / 3f) % 1f
            Box(
                Modifier
                    .size(150.dp)
                    .graphicsLayer {
                        val s = 1f + phase * 1.3f
                        scaleX = s
                        scaleY = s
                        alpha = (1f - phase) * 0.35f
                    }
                    .background(color, CircleShape),
            )
        }
        Box(
            Modifier
                .size(130.dp)
                .scale(pulse)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(52.dp),
            )
        }
    }
}

@Composable
private fun AnalyzingOrb() {
    Box(
        Modifier
            .size(130.dp)
            .background(MaterialTheme.colorScheme.secondary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
    }
}

/** Little equalizer whose bars grow with the live mic level, reinforcing "I'm hearing sound". */
@Composable
private fun Equalizer(level: Float, color: Color) {
    val transition = rememberInfiniteTransition(label = "eq")
    Row(
        modifier = Modifier.height(30.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (i in 0 until 5) {
            val a by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(430 + i * 130, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            val amp = 0.35f + 0.65f * level.coerceIn(0f, 1f)
            Box(
                Modifier
                    .width(6.dp)
                    .height((6f + 24f * a * amp).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun ListenStatus(phase: Phase, level: Float, onCancel: () -> Unit) {
    when (phase) {
        Phase.RECORDING -> {
            Equalizer(level = level, color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(10.dp))
            val msg = rememberCyclingMessage(
                messages = listOf(
                    tr("Ακούω το μωρό..."),
                    tr("Κράτα το κινητό κοντά του"),
                    tr("Συγκεντρώνω τον ήχο..."),
                    tr("Σχεδόν εκεί..."),
                ),
            )
            Text(msg, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(2.dp))
            Text(
                tr("Θα σταματήσω μόνο μου μόλις καταλάβω"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onCancel) { Text(tr("Άκυρο")) }
        }
        Phase.ANALYZING -> Text(tr("Αναλύω το κλάμα..."), style = MaterialTheme.typography.bodyLarge)
        Phase.IDLE -> Text(tr("Πάτα για να ακούσω το μωρό"), style = MaterialTheme.typography.bodyLarge)
        Phase.RESULT -> Text(tr("Αποτέλεσμα"), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Rotates through [messages] every ~1.8s. */
@Composable
private fun rememberCyclingMessage(messages: List<String>): String {
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1800)
            idx = (idx + 1) % messages.size
        }
    }
    return messages[idx.coerceIn(0, messages.lastIndex)]
}

@Composable
private fun ResultCard(
    analysis: CryAnalysis,
    feedbackGiven: Boolean,
    feedbackDeferred: Boolean,
    canReplay: Boolean,
    onReplay: () -> Unit,
    onCorrect: () -> Unit,
    onDefer: () -> Unit,
) {
    val result = analysis.result
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (!result.cryDetected) {
                Text(tr("Δεν άκουσα καθαρό κλάμα"), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    tr("Άκουσα για λίγα δευτερόλεπτα αλλά δεν ξεχώρισα κλάμα. Πάτα ξανά το μικρόφωνο και κράτα το κινητό πιο κοντά στο μωρό ή δοκίμασε σε πιο ήσυχο χώρο."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            val top = result.topReason!!
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(top.emoji, fontSize = 40.sp)
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(tr(top.displayName), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${tr("Βεβαιότητα")} ${(result.confidence * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            if (analysis.uncertain) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = tr(
                        if (result.engine == AnalysisEngine.HEURISTIC)
                            "Πρόχειρη εκτίμηση - δεν υπάρχει εκπαιδευμένο μοντέλο."
                        else "Δεν είμαι σίγουρο - δες και τις άλλες πιθανές αιτίες.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(tr(top.advice), style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onReplay,
                enabled = canReplay,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(tr("Άκου ξανά"), maxLines = 1)
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text(tr("Πιθανές αιτίες"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            result.scores.take(3).forEach { score ->
                ScoreBar(reason = score.reason, probability = score.probability)
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(12.dp))

            when {
                feedbackGiven -> Text(
                    tr("Ευχαριστώ! Θα μάθω από αυτό."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Medium,
                )
                feedbackDeferred -> Text(
                    tr("Κανένα πρόβλημα — θα σε ρωτήσουμε ξανά σε λίγα λεπτά, μόλις καταλάβεις."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                else -> FeedbackSection(onCorrect = onCorrect, onDefer = onDefer)
            }
        }
    }
}

@Composable
private fun FeedbackSection(
    onCorrect: () -> Unit,
    onDefer: () -> Unit,
) {
    Text(tr("Ξέρεις ήδη γιατί έκλαψε;"), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCorrect, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Check, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(tr("Ναι, σωστή"), maxLines = 1)
        }
        OutlinedButton(onClick = onDefer, modifier = Modifier.weight(1f)) {
            Text(tr("Δεν ξέρω ακόμα"), maxLines = 1)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        tr("Αν δεν ξέρεις ακόμα, θα σε ρωτήσουμε σε λίγα λεπτά μόλις καταλάβεις (π.χ. αφού το ταΐσεις κι ηρεμήσει). Μπορείς να το αλλάξεις και αργότερα από το Ιστορικό."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}

/**
 * Home banner asking the parent to confirm why the baby cried *earlier* - shown once they've
 * had a few minutes to figure it out. Confirming here also teaches the personalization engine.
 */
@Composable
private fun PendingConfirmCard(
    event: CryEvent,
    labels: List<CryReason>,
    babyName: String,
    onConfirm: (CryReason) -> Unit,
    onDismiss: () -> Unit,
) {
    val predicted = event.predictedIndex.takeIf { it in labels.indices }?.let { labels[it] }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                pendingTitle(babyName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${tr("Έκλαψε")} ${minutesAgoText(event.timestamp)}. ${tr("Τώρα που ξέρεις την αιτία, διάλεξέ την:")}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (predicted != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${tr("Η αρχική εκτίμηση:")} ${predicted.emoji} ${tr(predicted.displayName)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(12.dp))
            CryReason.canonicalOrder.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { reason ->
                        OutlinedButton(
                            onClick = { onConfirm(reason) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("${reason.emoji} ${tr(reason.displayName)}", maxLines = 1, fontSize = 12.sp)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(tr("Δεν ξέρω ακόμη"))
            }
        }
    }
}

private fun pendingTitle(babyName: String): String = when (currentAppLang) {
    AppLang.EN -> if (babyName.isNotBlank()) "Why did $babyName cry?" else "Why did baby cry earlier?"
    AppLang.EL -> if (babyName.isNotBlank()) "Γιατί έκλαψε ο/η $babyName;" else "Γιατί έκλαψε πριν;"
}

private fun minutesAgoText(ts: Long): String {
    val min = (System.currentTimeMillis() - ts) / 60_000L
    return when (currentAppLang) {
        AppLang.EN -> when {
            min < 1 -> "just now"
            min == 1L -> "1 minute ago"
            min < 60 -> "$min minutes ago"
            min < 120 -> "1 hour ago"
            min < 1440 -> "${min / 60} hours ago"
            else -> "${min / 1440} days ago"
        }
        AppLang.EL -> when {
            min < 1 -> "μόλις τώρα"
            min == 1L -> "πριν 1 λεπτό"
            min < 60 -> "πριν $min λεπτά"
            min < 120 -> "πριν 1 ώρα"
            min < 1440 -> "πριν ${min / 60} ώρες"
            else -> "πριν ${min / 1440} ημέρες"
        }
    }
}

@Composable
private fun ScoreBar(reason: CryReason, probability: Float) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${reason.emoji} ${tr(reason.displayName)}", style = MaterialTheme.typography.bodyMedium)
            Text("${(probability * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { probability.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

