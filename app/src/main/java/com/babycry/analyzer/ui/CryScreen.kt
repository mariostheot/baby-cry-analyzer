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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.mutableStateOf
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
import com.babycry.analyzer.data.TummyTimeEvent
import com.babycry.analyzer.model.AnalysisEngine
import com.babycry.analyzer.model.BabyProfile
import com.babycry.analyzer.model.DiaperType
import com.babycry.analyzer.ml.CryAnalysis
import com.babycry.analyzer.model.CryReason
import com.babycry.analyzer.model.TummyTime
import java.util.Calendar
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
    onTummyGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.home.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val pending by viewModel.pendingConfirmation.collectAsState()
    val soothing by viewModel.soothing.collectAsState()
    val playback by viewModel.playback.collectAsState()
    val feeding by viewModel.feeding.collectAsState()
    val tummy by viewModel.recentTummy.collectAsState()
    var showDiaper by remember { mutableStateOf(false) }

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
                text = "👶 " + profile.displayNameNominative(currentAppLang == AppLang.EN) + babyAgeSuffix(profile),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Mini-player: soothing sound keeps playing across screens, so surface a stop control
        // right here instead of making the parent dig back into the Soothe screen.
        if (soothing.playing != null) {
            Spacer(Modifier.height(16.dp))
            SoothingMiniPlayer(
                state = soothing,
                onStop = { viewModel.stopSoothing() },
                onOpen = onSoothe,
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
                profile = profile,
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
                playback = playback,
                onReplay = { viewModel.playLastRecording() },
                onPauseReplay = { viewModel.pauseReplay() },
                onResumeReplay = { viewModel.resumeReplay() },
                onCorrect = { viewModel.confirmPredictionCorrect() },
                onCorrectTo = { viewModel.correctTo(it) },
                onDefer = { viewModel.deferFeedback() },
            )
            Spacer(Modifier.height(16.dp))
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickAction(
                modifier = Modifier.weight(1f),
                icon = if (feeding.eventId == null) Icons.Filled.Restaurant else Icons.Filled.Stop,
                label = if (feeding.eventId == null) {
                    tr("Έναρξη ταΐσματος")
                } else {
                    "${tr("Τέλος ταΐσματος")}\n${feedingDurationLabel(feeding.elapsedSeconds)}"
                },
                onClick = { viewModel.toggleFeeding() },
            )
            QuickAction(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.BabyChangingStation,
                label = tr("Αλλαγή πάνας"),
                onClick = { showDiaper = true },
            )
            QuickAction(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.MusicNote,
                label = tr("Ηρέμησέ το"),
                onClick = onSoothe,
            )
        }

        Spacer(Modifier.height(12.dp))
        TummyTimeCard(
            doneToday = tummyDoneToday(tummy),
            goal = TummyTime.dailyGoal(profile.ageDays()),
            onLog = { viewModel.logTummy() },
            onOpenGuide = onTummyGuide,
        )

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

    if (showDiaper) {
        AlertDialog(
            onDismissRequest = { showDiaper = false },
            title = { Text(tr("Αλλαγή πάνας")) },
            text = {
                Column {
                    Text(
                        tr("Τι είχε η πάνα; Βοηθά να βλέπεις μοτίβα (π.χ. πόσο συχνά κάνει κακά)."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    DiaperType.entries.forEach { t ->
                        OutlinedButton(
                            onClick = {
                                viewModel.logDiaper(t)
                                showDiaper = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            Text("${t.emoji}  ${tr(t.displayName)}", maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDiaper = false }) { Text(tr("Άκυρο")) }
            },
        )
    }
}

@Composable
private fun QuickAction(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

private fun feedingDurationLabel(totalSeconds: Long): String {
    val minutes = (totalSeconds.coerceAtLeast(0L) / 60L)
    val seconds = totalSeconds.coerceAtLeast(0L) % 60L
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Home card that tracks tummy time against the age-appropriate daily goal. Count-based: the
 * parent taps "I did it" and sees "2 / 5 today · 3 to go". Tapping the guide link opens the
 * informational Tummy Time screen.
 */
@Composable
private fun TummyTimeCard(
    doneToday: Int,
    goal: Int,
    onLog: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val reached = goal > 0 && doneToday >= goal
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.SelfImprovement,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    tr("Tummy Time"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenGuide) { Text(tr("Οδηγός")) }
            }
            Spacer(Modifier.height(2.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("Σήμερα"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Text(
                    "$doneToday / $goal",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (goal > 0) (doneToday.toFloat() / goal).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tummyRemainingText(doneToday, goal),
                style = MaterialTheme.typography.bodySmall,
                color = if (reached) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onLog, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(tr("Έκανα Tummy Time"), maxLines = 1)
            }
        }
    }
}

/** Tummy-time sessions logged since the start of today. */
private fun tummyDoneToday(events: List<TummyTimeEvent>): Int {
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return events.count { it.timestamp >= start }
}

private fun tummyRemainingText(done: Int, goal: Int): String {
    val remaining = (goal - done).coerceAtLeast(0)
    return when (currentAppLang) {
        AppLang.EN -> when (remaining) {
            0 -> "Goal reached for today \uD83C\uDF89"
            1 -> "1 more session to go"
            else -> "$remaining more sessions to go"
        }
        AppLang.EL -> when (remaining) {
            0 -> "Πέτυχες τον στόχο για σήμερα \uD83C\uDF89"
            1 -> "Απομένει 1 ακόμη φορά"
            else -> "Απομένουν $remaining ακόμη φορές"
        }
    }
}

private fun babyAgeSuffix(profile: BabyProfile): String {
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

/**
 * Compact "now playing" card for the soothing sound, shown on Home so the parent can see the
 * remaining time and stop it with one tap without going back to the Soothe screen. Tapping the
 * card body opens the full Soothe screen.
 */
@Composable
private fun SoothingMiniPlayer(
    state: SoothingUiState,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    val playing = state.playing ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${playing.emoji} ${tr(playing.displayName)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    if (state.remainingSec > 0)
                        "${tr("Απομένει:")} ${"%d:%02d".format(state.remainingSec / 60, state.remainingSec % 60)}"
                    else tr("Παίζει (χωρίς χρονικό όριο)"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                )
            }
            FilledTonalIconButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = tr("Σταμάτα"))
            }
        }
    }
}

@Composable
private fun ResultCard(
    analysis: CryAnalysis,
    feedbackGiven: Boolean,
    feedbackDeferred: Boolean,
    canReplay: Boolean,
    playback: PlaybackUiState,
    onReplay: () -> Unit,
    onPauseReplay: () -> Unit,
    onResumeReplay: () -> Unit,
    onCorrect: () -> Unit,
    onCorrectTo: (CryReason) -> Unit,
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReplay,
                    enabled = canReplay,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(tr("Άκου ξανά"), maxLines = 1)
                }
                if (playback.key == "last") {
                    OutlinedButton(
                        onClick = if (playback.paused) onResumeReplay else onPauseReplay,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            if (playback.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(tr(if (playback.paused) "Συνέχεια" else "Παύση"), maxLines = 1)
                    }
                }
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
                else -> FeedbackSection(
                    onCorrect = onCorrect,
                    onCorrectTo = onCorrectTo,
                    onDefer = onDefer,
                )
            }
        }
    }
}

@Composable
private fun FeedbackSection(
    onCorrect: () -> Unit,
    onCorrectTo: (CryReason) -> Unit,
    onDefer: () -> Unit,
) {
    // When the parent knows the estimate was wrong, we reveal the reason chips so they can pick
    // the real cause (this both fixes the stats and teaches the personalization engine).
    var choosing by remember { mutableStateOf(false) }

    if (choosing) {
        Text(tr("Διάλεξε τη σωστή αιτία:"), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        CryReason.canonicalOrder.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { reason ->
                    OutlinedButton(
                        onClick = { onCorrectTo(reason) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("${reason.emoji} ${tr(reason.displayName)}", maxLines = 1, fontSize = 12.sp)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = { choosing = false }) { Text(tr("Πίσω")) }
        return
    }

    Text(tr("Ήταν σωστή η εκτίμηση;"), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCorrect, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Check, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(tr("Ναι, σωστή"), maxLines = 1)
        }
        OutlinedButton(onClick = { choosing = true }, modifier = Modifier.weight(1f)) {
            Text(tr("Όχι, άλλη αιτία"), maxLines = 1)
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onDefer, modifier = Modifier.fillMaxWidth()) {
        Text(tr("Δεν ξέρω ακόμα"), maxLines = 1)
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
    profile: BabyProfile,
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
                pendingTitle(profile),
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

private fun pendingTitle(profile: BabyProfile): String = when (currentAppLang) {
    AppLang.EN -> if (profile.hasName) "Why did ${profile.name} cry?" else "Why did baby cry earlier?"
    AppLang.EL -> if (profile.hasName) "Γιατί έκλαψε ${profile.displayNameNominative(false)};" else "Γιατί έκλαψε πριν;"
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

