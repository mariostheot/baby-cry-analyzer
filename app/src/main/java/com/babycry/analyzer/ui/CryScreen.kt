package com.babycry.analyzer.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.babycry.analyzer.model.AnalysisEngine
import com.babycry.analyzer.ml.CryAnalysis
import com.babycry.analyzer.model.CryReason
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    viewModel: CryViewModel,
    onListen: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.home.collectAsState()
    val profile by viewModel.profile.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Άκου το μωρό",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (viewModel.hasModel) "AI μοντέλο ενεργό" else "Πρόχειρη εκτίμηση (χωρίς μοντέλο)",
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

        Spacer(Modifier.height(28.dp))
        ListenButton(phase = state.phase, level = state.level, onClick = onListen)
        Spacer(Modifier.height(16.dp))
        Text(
            text = when (state.phase) {
                Phase.IDLE -> "Πάτα μία φορά — ακούω και σταματάω μόνο μου"
                Phase.RECORDING -> "Ακούω... θα σταματήσω μόλις καταλάβω"
                Phase.ANALYZING -> "Αναλύω..."
                Phase.RESULT -> "Αποτέλεσμα"
            },
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(Modifier.height(24.dp))

        if (state.phase == Phase.RESULT && state.analysis != null) {
            ResultCard(
                analysis = state.analysis!!,
                feedbackGiven = state.feedbackGiven,
                canReplay = viewModel.canReplay,
                onReplay = { viewModel.playLastRecording() },
                onShare = onShare,
                onCorrect = { viewModel.confirmPredictionCorrect() },
                onCorrectTo = { viewModel.correctTo(it) },
            )
            Spacer(Modifier.height(16.dp))
        }

        FilledTonalButton(onClick = { viewModel.logFeeding() }) {
            Icon(Icons.Filled.Restaurant, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Τάισμα τώρα")
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Ενημερωτικό βοήθημα, όχι ιατρική συμβουλή. Αν ανησυχείς για την υγεία του μωρού, ρώτησε παιδίατρο.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

private fun babyAgeSuffix(profile: com.babycry.analyzer.model.BabyProfile): String {
    val months = profile.ageMonths() ?: return ""
    return when {
        months < 1 -> " · νεογέννητο"
        months < 24 -> " · $months μηνών"
        else -> " · ${months / 12} ετών"
    }
}

@Composable
private fun ListenButton(phase: Phase, level: Float, onClick: () -> Unit) {
    val pulse by animateFloatAsState(
        targetValue = if (phase == Phase.RECORDING) 1f + level * 0.35f else 1f,
        label = "pulse",
    )
    val color = when (phase) {
        Phase.RECORDING -> MaterialTheme.colorScheme.tertiary
        Phase.ANALYZING -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(pulse)
                .background(color.copy(alpha = 0.15f), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(130.dp)
                .background(color, CircleShape)
                .clickable(enabled = phase != Phase.ANALYZING, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                Phase.ANALYZING -> CircularProgressIndicator(color = Color.White)
                Phase.RECORDING -> Icon(
                    Icons.Filled.Stop, contentDescription = "Διακοπή",
                    tint = Color.White, modifier = Modifier.size(56.dp),
                )
                else -> Icon(
                    Icons.Filled.Mic, contentDescription = "Ηχογράφηση",
                    tint = Color.White, modifier = Modifier.size(56.dp),
                )
            }
        }
    }
}

@Composable
private fun ResultCard(
    analysis: CryAnalysis,
    feedbackGiven: Boolean,
    canReplay: Boolean,
    onReplay: () -> Unit,
    onShare: () -> Unit,
    onCorrect: () -> Unit,
    onCorrectTo: (CryReason) -> Unit,
) {
    val result = analysis.result
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (!result.cryDetected) {
                Text("Δεν άκουσα καθαρό κλάμα", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Δοκίμασε πιο κοντά στο μωρό ή σε πιο ήσυχο χώρο.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            val top = result.topReason!!
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(top.emoji, fontSize = 40.sp)
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(top.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Βεβαιότητα ${(result.confidence * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            if (analysis.uncertain) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (result.engine == AnalysisEngine.HEURISTIC)
                        "Πρόχειρη εκτίμηση - δεν υπάρχει εκπαιδευμένο μοντέλο."
                    else "Δεν είμαι σίγουρο - δες και τις άλλες πιθανές αιτίες.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(top.advice, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReplay,
                    enabled = canReplay,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Άκου ξανά", maxLines = 1)
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Κοινοποίηση", maxLines = 1)
                }
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("Πιθανές αιτίες", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            result.scores.take(3).forEach { score ->
                ScoreBar(reason = score.reason, probability = score.probability)
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(12.dp))

            if (feedbackGiven) {
                Text(
                    "Ευχαριστώ! Θα μάθω από αυτό.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                FeedbackSection(onCorrect = onCorrect, onCorrectTo = onCorrectTo)
            }
        }
    }
}

@Composable
private fun FeedbackSection(
    onCorrect: () -> Unit,
    onCorrectTo: (CryReason) -> Unit,
) {
    Text("Ήταν σωστό;", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Button(onClick = onCorrect, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Check, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Ναι, σωστό")
    }
    Spacer(Modifier.height(8.dp))
    Text("...ή διάλεξε τη σωστή αιτία:", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Column {
        CryReason.canonicalOrder.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { reason ->
                    OutlinedButton(
                        onClick = { onCorrectTo(reason) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("${reason.emoji} ${reason.displayName}", maxLines = 1, fontSize = 12.sp)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ScoreBar(reason: CryReason, probability: Float) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${reason.emoji} ${reason.displayName}", style = MaterialTheme.typography.bodyMedium)
            Text("${(probability * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { probability.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

