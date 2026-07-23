package com.babycry.analyzer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.babycry.analyzer.audio.AudioPlayer
import com.babycry.analyzer.audio.AudioRecorder
import com.babycry.analyzer.audio.SoothingPlayer
import com.babycry.analyzer.audio.SoundType
import com.babycry.analyzer.data.CryEvent
import com.babycry.analyzer.data.CryRepository
import com.babycry.analyzer.data.DiaperEvent
import com.babycry.analyzer.data.FeedingEvent
import com.babycry.analyzer.data.SleepEvent
import com.babycry.analyzer.data.StatsSummary
import com.babycry.analyzer.data.TummyTimeEvent
import com.babycry.analyzer.data.WeightEvent
import com.babycry.analyzer.data.HeightEvent
import com.babycry.analyzer.insights.CareInsightSummary
import com.babycry.analyzer.ml.CryAnalysis
import com.babycry.analyzer.model.AnalysisEngine
import com.babycry.analyzer.model.BabyGender
import com.babycry.analyzer.model.BabyProfile
import com.babycry.analyzer.model.CryReason
import com.babycry.analyzer.model.DiaperType
import com.babycry.analyzer.notify.ConfirmReminder
import com.babycry.analyzer.notify.FeedReminder
import com.babycry.analyzer.notify.TummyReminder
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.trS
import java.io.OutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Phase { IDLE, RECORDING, ANALYZING, RESULT }

data class HomeUiState(
    val phase: Phase = Phase.IDLE,
    val level: Float = 0f,
    val analysis: CryAnalysis? = null,
    val eventId: Long? = null,
    val feedbackGiven: Boolean = false,
    /** Parent tapped "don't know yet": stop asking on the result card; the delayed
     *  reminder we already scheduled will follow up later. */
    val feedbackDeferred: Boolean = false,
    val message: String? = null,
)

/** State of the soothing-sounds player (null [playing] = stopped). */
data class SoothingUiState(
    val playing: SoundType? = null,
    val remainingSec: Int = 0,
)

data class PlaybackUiState(
    val key: String? = null,
    val paused: Boolean = false,
)

/** A timed feeding session for the currently selected baby. */
data class FeedingUiState(
    val eventId: Long? = null,
    val startedAt: Long = 0,
    val elapsedSeconds: Long = 0,
)

/** A timed nap/sleep session for the currently selected baby. */
data class SleepUiState(
    val eventId: Long? = null,
    val startedAt: Long = 0,
    val elapsedSeconds: Long = 0,
)

/** Reactive loader state for on-device care-pattern insights. */
data class CareInsightsUiState(
    val loading: Boolean = true,
    val summary: CareInsightSummary? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CryRepository.get(app)
    private val recorder = AudioRecorder()
    private val player = AudioPlayer()
    private val soother = SoothingPlayer()
    private var soothingJob: Job? = null
    private var feedingJob: Job? = null
    private var sleepJob: Job? = null
    private var lastWaveform: FloatArray? = null

    @Volatile
    private var cancelRequested = false

    val labels: List<CryReason> get() = repo.labels
    val hasModel: Boolean get() = repo.hasModel

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _personalizationEnabled = MutableStateFlow(repo.isPersonalizationEnabled())
    val personalizationEnabled: StateFlow<Boolean> = _personalizationEnabled.asStateFlow()

    private val _profile = MutableStateFlow(repo.getProfile())
    val profile: StateFlow<BabyProfile> = _profile.asStateFlow()

    private val _profiles = MutableStateFlow(repo.getProfiles())
    val profiles: StateFlow<List<BabyProfile>> = _profiles.asStateFlow()

    private val _soothing = MutableStateFlow(SoothingUiState())
    val soothing: StateFlow<SoothingUiState> = _soothing.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

    private val _feeding = MutableStateFlow(FeedingUiState())
    val feeding: StateFlow<FeedingUiState> = _feeding.asStateFlow()

    private val _sleep = MutableStateFlow(SleepUiState())
    val sleep: StateFlow<SleepUiState> = _sleep.asStateFlow()

    private val _onboardingComplete = MutableStateFlow(repo.isOnboardingComplete())
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    // A past cry still awaiting the parent's "why did it cry?" confirmation.
    private val _pending = MutableStateFlow<CryEvent?>(null)
    val pendingConfirmation: StateFlow<CryEvent?> = _pending.asStateFlow()

    private val _language = MutableStateFlow(repo.getLanguage())
    val language: StateFlow<AppLang> = _language.asStateFlow()

    private val _lastBackupAt = MutableStateFlow(repo.lastBackupAt())
    val lastBackupAt: StateFlow<Long> = _lastBackupAt.asStateFlow()

    val canReplay: Boolean get() = lastWaveform != null

    val feedbackCount: StateFlow<Int> = _profile.flatMapLatest { repo.feedbackCount(it.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val recentEvents: StateFlow<List<CryEvent>> = _profile.flatMapLatest { repo.recentEvents(it.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentFeedings: StateFlow<List<FeedingEvent>> = _profile.flatMapLatest { repo.recentFeedings(it.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentDiapers: StateFlow<List<DiaperEvent>> = _profile.flatMapLatest { repo.recentDiapers(it.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentTummy: StateFlow<List<TummyTimeEvent>> = _profile.flatMapLatest { repo.recentTummy(it.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentSleep: StateFlow<List<SleepEvent>> = _profile.flatMapLatest { repo.recentSleep(it.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentWeights: StateFlow<List<WeightEvent>> = _profile.flatMapLatest { repo.recentWeights(it.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentHeights: StateFlow<List<HeightEvent>> = _profile.flatMapLatest { repo.recentHeights(it.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // kotlinx-coroutines only offers typed combine() up to 5 flows, so fold the four care
    // event streams into one change-trigger first and combine that with profile + language.
    private val careEventsChanged: kotlinx.coroutines.flow.Flow<Unit> = combine(
        recentEvents,
        recentFeedings,
        recentDiapers,
        recentSleep,
    ) { _, _, _, _ -> Unit }

    val careInsights: StateFlow<CareInsightsUiState> = combine(
        _profile,
        _language,
        careEventsChanged,
    ) { profile, _, _ -> profile.id }
        .flatMapLatest { profileId ->
            flow {
                emit(CareInsightsUiState(loading = true, summary = null))
                val summary = runCatching { repo.careInsights(profileId) }.getOrNull()
                emit(
                    CareInsightsUiState(
                        loading = false,
                        summary = summary,
                    ),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CareInsightsUiState())

    // True once there is anything worth backing up on this device.
    // kotlinx-coroutines only offers typed combine() up to 5 flows, so nest the last two.
    private val hasTrackedData: kotlinx.coroutines.flow.Flow<Boolean> = combine(
        combine(
            recentEvents,
            recentFeedings,
            recentDiapers,
            recentSleep,
            recentTummy,
        ) { e, f, d, s, t ->
            e.isNotEmpty() || f.isNotEmpty() || d.isNotEmpty() || s.isNotEmpty() || t.isNotEmpty()
        },
        recentWeights,
        recentHeights,
    ) { base, weights, heights ->
        base || weights.isNotEmpty() || heights.isNotEmpty()
    }

    /**
     * Nudge the parent to back up when there is data to lose and either no backup was ever made
     * or the last one is older than a month — so a phone change doesn't wipe the baby's history.
     */
    val backupOverdue: StateFlow<Boolean> = combine(_lastBackupAt, hasTrackedData) { last, hasData ->
        hasData && (last <= 0L || System.currentTimeMillis() - last > BACKUP_STALE_MS)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _tummyReminderEnabled = MutableStateFlow(repo.isTummyReminderEnabled())
    val tummyReminderEnabled: StateFlow<Boolean> = _tummyReminderEnabled.asStateFlow()

    private val _tummyReminderHourAm = MutableStateFlow(repo.tummyReminderHourAm())
    val tummyReminderHourAm: StateFlow<Int> = _tummyReminderHourAm.asStateFlow()

    private val _tummyReminderHourPm = MutableStateFlow(repo.tummyReminderHourPm())
    val tummyReminderHourPm: StateFlow<Int> = _tummyReminderHourPm.asStateFlow()

    init {
        currentAppLang = repo.getLanguage()
        viewModelScope.launch {
            refreshProfiles()
            refreshActiveProfileState()
        }
    }

    /** Re-check whether there's a past cry waiting for the parent to confirm its reason. */
    fun refreshPending() {
        viewModelScope.launch { refreshPendingNow() }
    }

    private suspend fun refreshPendingNow() {
        _pending.value = repo.pendingConfirmation()
    }

    /**
     * Shazam-style: tap the mic to start. There is no manual stop button - the analysis
     * auto-finishes as soon as it's confident (or when the max listen time is reached).
     */
    fun onListenTapped() {
        when (_home.value.phase) {
            Phase.IDLE -> startListening()
            Phase.RESULT -> retryListening()
            else -> Unit
        }
    }

    /**
     * The result is still on screen, so another Listen tap means "try that same cry again".
     * Drop its unconfirmed event/clip and cancel its delayed question before recording anew.
     * Confirmed history records are never removed by this shortcut.
     */
    private fun retryListening() {
        val previous = _home.value
        _home.update { it.copy(phase = Phase.ANALYZING, message = null) } // prevent double taps
        viewModelScope.launch {
            previous.eventId?.let { eventId ->
                val profileId = repo.discardUnconfirmedEvent(eventId)
                if (profileId != null) {
                    ConfirmReminder.cancel(getApplication<Application>(), profileId, eventId)
                }
                if (_pending.value?.id == eventId) _pending.value = null
            }
            lastWaveform = null
            refreshPendingNow()
            startListening()
        }
    }

    /** Lets the user back out of an in-progress listen without analysing anything. */
    fun cancelListening() {
        if (_home.value.phase == Phase.RECORDING) {
            cancelRequested = true
            recorder.stop()
        }
    }

    private fun startListening() {
        cancelRequested = false
        stopSoothing() // don't let the soothing sound bleed into the recording
        player.stop()  // stop any in-progress replay so it doesn't overlap / leak into the mic
        _playback.value = PlaybackUiState()
        viewModelScope.launch {
            _home.update {
                HomeUiState(phase = Phase.RECORDING, level = 0f)
            }
            val waveform = try {
                recorder.record(
                    maxDurationMs = MAX_RECORD_MS,
                    minDurationMs = MIN_LISTEN_MS,
                    onLevel = { level -> _home.update { it.copy(level = level) } },
                    shouldFinish = { partial ->
                        // Shazam-style: stop as soon as we're confident it's a cry. Context
                        // priors are skipped here (they don't affect cry detection) to avoid a
                        // DB hit on every probe; the final analysis below still applies them.
                        val probe = repo.analyze(
                            waveform = partial,
                            personalizationEnabled = _personalizationEnabled.value,
                            contextEnabled = false,
                        )
                        when {
                            !probe.result.cryDetected -> false // no cry yet -> keep listening
                            probe.result.engine == AnalysisEngine.HEURISTIC -> true // can't get surer
                            else -> !probe.uncertain           // model: stop once confident
                        }
                    },
                )
            } catch (t: Throwable) {
                _home.update {
                    HomeUiState(phase = Phase.IDLE, message = t.message ?: trS("Σφάλμα ηχογράφησης"))
                }
                return@launch
            }

            // User backed out mid-listen: drop the clip and go quietly back to idle.
            if (cancelRequested) {
                cancelRequested = false
                _home.update { HomeUiState(phase = Phase.IDLE) }
                return@launch
            }

            lastWaveform = waveform
            _home.update { it.copy(phase = Phase.ANALYZING, level = 0f) }
            try {
                val analysis = repo.analyze(
                    waveform = waveform,
                    personalizationEnabled = _personalizationEnabled.value,
                    contextEnabled = true,
                )
                val eventId = repo.saveEvent(analysis, waveform)
                val noCry = !analysis.result.cryDetected
                _home.update {
                    it.copy(
                        phase = Phase.RESULT,
                        analysis = analysis,
                        eventId = eventId,
                        message = if (noCry) {
                            trS("Άκουσα αλλά δεν ξεχώρισα καθαρό κλάμα. Δοκίμασε ξανά, πιο κοντά στο μωρό ή σε πιο ήσυχο χώρο.")
                        } else null,
                    )
                }
                // We don't pester for a reason right now (the parent rarely knows yet). Instead
                // we remember this cry and remind them in a few minutes to confirm the real cause.
                if (!noCry) {
                    _pending.value = repo.pendingConfirmation()
                    ConfirmReminder.schedule(
                        getApplication<Application>(),
                        REMINDER_DELAY_MIN,
                        repo.currentProfileId(),
                        eventId,
                    )
                }
            } catch (t: Throwable) {
                // Never let an inference error kill the app: surface it and reset.
                _home.update {
                    HomeUiState(
                        phase = Phase.IDLE,
                        message = "${trS("Σφάλμα ανάλυσης:")} ${t.message ?: t.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun confirmPredictionCorrect() {
        val state = _home.value
        val reason = state.analysis?.result?.topReason ?: return
        submit(reason)
    }

    fun correctTo(reason: CryReason) = submit(reason)

    /**
     * "Don't know yet" on the fresh result: we don't record anything now. The reminder we
     * scheduled when the cry was detected will ask again in a few minutes, and the cry stays
     * editable from History.
     */
    fun deferFeedback() {
        _home.update { it.copy(feedbackDeferred = true) }
    }

    private fun submit(reason: CryReason) {
        val state = _home.value
        val eventId = state.eventId ?: return
        val embedding = state.analysis?.embedding
        viewModelScope.launch {
            val profileId = repo.profileIdForEvent(eventId) ?: repo.currentProfileId()
            repo.confirm(eventId, reason, embedding)
            if (_pending.value?.id == eventId) _pending.value = null
            ConfirmReminder.cancel(getApplication<Application>(), profileId, eventId)
            refreshPendingNow()
            _home.update { it.copy(feedbackGiven = true, message = trS("Ευχαριστώ! Θα μάθω από αυτό.")) }
        }
    }

    /** Confirm/correct the reason for the pending (previous) cry, from the home banner. */
    fun confirmPending(reason: CryReason) {
        val pending = _pending.value ?: return
        viewModelScope.launch {
            repo.setReason(pending.id, reason)
            ConfirmReminder.cancel(getApplication<Application>(), pending.profileId, pending.id)
            refreshPendingNow()
            _home.update {
                it.copy(message = recordedMessage(reason))
            }
        }
    }

    /** "Not sure yet" - stop asking for now (still editable later from History). */
    fun dismissPending() {
        val pending = _pending.value ?: return
        repo.dismissPending(pending.profileId)
        ConfirmReminder.cancel(getApplication<Application>(), pending.profileId, pending.id)
        viewModelScope.launch { refreshPendingNow() }
    }

    /** Set/correct the reason of any past cry (from History). */
    fun setReasonForEvent(eventId: Long, reason: CryReason) {
        viewModelScope.launch {
            repo.setReason(eventId, reason)
            repo.profileIdForEvent(eventId)?.let { profileId ->
                ConfirmReminder.cancel(getApplication<Application>(), profileId, eventId)
            }
            refreshPendingNow()
            _home.update { it.copy(message = trS("Ενημερώθηκε η αιτία.")) }
        }
    }

    suspend fun datasetInfo(): Pair<Int, Long> = repo.datasetInfo()

    suspend fun backupRecordingCount(): Int = repo.backupRecordingCount()

    suspend fun writeDatasetZip(out: OutputStream): Int = repo.writeDatasetZip(out)

    /**
     * The Home feeding button starts/stops one session for the selected baby. The session lives
     * in Room from the first tap, so its timer can be restored after an app restart.
     */
    fun toggleFeeding() {
        viewModelScope.launch {
            val profileId = _profile.value.id
            val active = repo.activeFeeding(profileId)
            if (active == null) {
                val started = repo.startFeeding(profileId)
                FeedReminder.cancel(getApplication<Application>(), profileId)
                startFeedingTimer(started)
                _home.update { it.copy(message = trS("Άρχισε το τάισμα. Πάτησε ξανά όταν τελειώσει.")) }
            } else {
                val completed = repo.stopFeeding(profileId) ?: return@launch
                stopFeedingTimer()
                _home.update { it.copy(message = feedingCompleteMessage(completed.durationMs)) }
                scheduleFeedReminder()
            }
        }
    }

    fun updateFeeding(eventId: Long, startedAt: Long, durationMs: Long) {
        viewModelScope.launch {
            if (repo.updateFeeding(eventId, startedAt, durationMs)) {
                _home.update { it.copy(message = trS("Ενημερώθηκε το τάισμα.")) }
                scheduleFeedReminder()
            }
        }
    }

    /**
     * The Home sleep button starts/stops one session for the selected baby. The session lives
     * in Room from the first tap, so its timer can be restored after an app restart.
     */
    fun toggleSleep() {
        viewModelScope.launch {
            val profileId = _profile.value.id
            val active = repo.activeSleep(profileId)
            if (active == null) {
                val started = repo.startSleep(profileId)
                startSleepTimer(started)
                _home.update { it.copy(message = trS("Άρχισε ο ύπνος. Πάτησε ξανά όταν ξυπνήσει.")) }
            } else {
                val completed = repo.stopSleep(profileId) ?: return@launch
                stopSleepTimer()
                _home.update { it.copy(message = sleepCompleteMessage(completed.durationMs)) }
            }
        }
    }

    fun updateSleep(eventId: Long, startedAt: Long, durationMs: Long) {
        viewModelScope.launch {
            if (repo.updateSleep(eventId, startedAt, durationMs)) {
                _home.update { it.copy(message = trS("Ενημερώθηκε ο ύπνος.")) }
            }
        }
    }

    private suspend fun refreshActiveSleep() {
        val active = repo.activeSleep(_profile.value.id)
        if (active == null) stopSleepTimer() else startSleepTimer(active)
    }

    private fun startSleepTimer(event: SleepEvent) {
        sleepJob?.cancel()
        fun currentState() = SleepUiState(
            eventId = event.id,
            startedAt = event.timestamp,
            elapsedSeconds = ((System.currentTimeMillis() - event.timestamp) / 1_000L).coerceAtLeast(0L),
        )
        _sleep.value = currentState()
        sleepJob = viewModelScope.launch {
            while (isActive && _sleep.value.eventId == event.id) {
                _sleep.value = currentState()
                delay(1_000L)
            }
        }
    }

    private fun stopSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleep.value = SleepUiState()
    }

    private suspend fun refreshActiveFeeding() {
        val active = repo.activeFeeding(_profile.value.id)
        if (active == null) stopFeedingTimer() else startFeedingTimer(active)
    }

    private fun startFeedingTimer(event: FeedingEvent) {
        feedingJob?.cancel()
        fun currentState() = FeedingUiState(
            eventId = event.id,
            startedAt = event.timestamp,
            elapsedSeconds = ((System.currentTimeMillis() - event.timestamp) / 1_000L).coerceAtLeast(0L),
        )
        _feeding.value = currentState()
        feedingJob = viewModelScope.launch {
            while (isActive && _feeding.value.eventId == event.id) {
                _feeding.value = currentState()
                delay(1_000L)
            }
        }
    }

    private fun stopFeedingTimer() {
        feedingJob?.cancel()
        feedingJob = null
        _feeding.value = FeedingUiState()
    }

    fun logDiaper(type: DiaperType) {
        viewModelScope.launch {
            repo.logDiaper(type)
            _home.update { it.copy(message = trS("Καταγράφηκε η αλλαγή πάνας.")) }
        }
    }

    fun logTummy() {
        viewModelScope.launch {
            repo.logTummy()
            _home.update { it.copy(message = trS("Καταγράφηκε το tummy time. Μπράβο!")) }
        }
    }

    fun addWeight(grams: Int, timestamp: Long) {
        viewModelScope.launch {
            repo.logWeight(grams, timestamp)
            _home.update { it.copy(message = trS("Καταγράφηκε το βάρος.")) }
        }
    }

    fun updateWeight(id: Long, grams: Int, timestamp: Long) {
        viewModelScope.launch {
            repo.updateWeight(id, grams, timestamp)
        }
    }

    fun deleteWeight(id: Long) {
        viewModelScope.launch {
            repo.deleteWeight(id)
        }
    }

    fun addHeight(millimeters: Int, timestamp: Long) {
        viewModelScope.launch {
            repo.logHeight(millimeters, timestamp)
            _home.update { it.copy(message = trS("Καταγράφηκε το ύψος.")) }
        }
    }

    fun updateHeight(id: Long, millimeters: Int, timestamp: Long) {
        viewModelScope.launch {
            repo.updateHeight(id, millimeters, timestamp)
        }
    }

    fun deleteHeight(id: Long) {
        viewModelScope.launch {
            repo.deleteHeight(id)
        }
    }

    /** Age-appropriate tummy-time sessions/day for the active baby. */
    fun tummyGoal(): Int = repo.tummyDailyGoal()

    fun scheduleTummyReminder() {
        TummyReminder.schedule(getApplication<Application>(), force = false)
    }

    fun setTummyReminderEnabled(enabled: Boolean) {
        repo.setTummyReminderEnabled(enabled)
        _tummyReminderEnabled.value = enabled
        if (enabled) TummyReminder.schedule(getApplication<Application>(), force = true)
        else TummyReminder.cancel(getApplication<Application>())
    }

    fun setTummyReminderHourAm(hour: Int) {
        repo.setTummyReminderHourAm(hour)
        _tummyReminderHourAm.value = repo.tummyReminderHourAm()
        if (_tummyReminderEnabled.value) {
            TummyReminder.schedule(getApplication<Application>(), force = true)
        }
    }

    fun setTummyReminderHourPm(hour: Int) {
        repo.setTummyReminderHourPm(hour)
        _tummyReminderHourPm.value = repo.tummyReminderHourPm()
        if (_tummyReminderEnabled.value) {
            TummyReminder.schedule(getApplication<Application>(), force = true)
        }
    }

    /**
     * (Re)schedule the "feeding time is near" heads-up from the last logged feed. Safe to call
     * on app start; cancels itself when there's nothing to remind about.
     */
    fun scheduleFeedReminder() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            for (profile in repo.getProfiles()) {
                val plan = repo.feedReminderPlan(profile.id)
                if (plan == null) {
                    FeedReminder.cancel(app, profile.id)
                } else {
                    FeedReminder.schedule(app, plan.first, plan.second, profile.id)
                }
            }
        }
    }

    private fun schedulePendingConfirmations() {
        val app = getApplication<Application>()
        for (profile in repo.getProfiles()) {
            for (eventId in repo.pendingEventIds(profile.id)) {
                ConfirmReminder.schedule(app, REMINDER_DELAY_MIN, profile.id, eventId)
            }
        }
    }

    /** Play back the clip that was just analyzed. */
    fun playLastRecording() {
        val wave = lastWaveform ?: return
        playWaveform("last", wave)
    }

    fun pauseReplay() {
        player.pause()
        _playback.update { if (it.key != null) it.copy(paused = true) else it }
    }

    fun resumeReplay() {
        player.resume()
        _playback.update { if (it.key != null) it.copy(paused = false) else it }
    }

    private fun playWaveform(key: String, wave: FloatArray) {
        viewModelScope.launch {
            _playback.value = PlaybackUiState(key = key, paused = false)
            player.play(wave)
            _playback.update { if (it.key == key) PlaybackUiState() else it }
        }
    }

    // ---- Saved recordings library --------------------------------------------

    /** Confirmed cries that still have a saved recording (reason + time + playable clip). */
    suspend fun libraryEvents(): List<CryEvent> = repo.libraryEvents()

    /** Replay a stored recording from the library. */
    fun playStoredClip(eventId: Long) {
        viewModelScope.launch {
            val wave = repo.readClipSamples(eventId) ?: return@launch
            playWaveform("event:$eventId", wave)
        }
    }

    /** A shareable text summary of the current result, or null if there is nothing to share. */
    fun shareSummary(): String? {
        val analysis = _home.value.analysis ?: return null
        val r = analysis.result
        if (!r.cryDetected) return trS("«NiniSense»: δεν ανιχνεύτηκε καθαρό κλάμα.")
        val top = r.topReason ?: return null
        val sb = StringBuilder()
        sb.append(trS("«NiniSense» — αποτέλεσμα")).append("\n")
        sb.append("${top.emoji} ${trS(top.displayName)} (${(r.confidence * 100).roundToInt()}%)\n\n")
        sb.append(trS("Πιθανές αιτίες:")).append("\n")
        r.scores.take(3).forEach {
            sb.append("• ${trS(it.reason.displayName)}: ${(it.probability * 100).roundToInt()}%\n")
        }
        sb.append("\n${trS(top.advice)}")
        return sb.toString()
    }

    // ---- Soothing sounds -----------------------------------------------------

    /** Start a soothing sound; [minutes] == 0 means play until stopped. */
    fun playSoothing(type: SoundType, minutes: Int) {
        player.stop() // don't overlap a cry replay with the soothing sound
        _playback.value = PlaybackUiState()
        soothingJob?.cancel()
        soother.start(type)
        _soothing.value = SoothingUiState(playing = type, remainingSec = minutes * 60)
        if (minutes > 0) {
            soothingJob = viewModelScope.launch {
                var left = minutes * 60
                while (left > 0 && _soothing.value.playing == type) {
                    delay(1000)
                    left--
                    _soothing.update { if (it.playing == type) it.copy(remainingSec = left) else it }
                }
                if (_soothing.value.playing == type) stopSoothing()
            }
        }
    }

    fun stopSoothing() {
        soothingJob?.cancel()
        soothingJob = null
        soother.stop()
        _soothing.value = SoothingUiState()
    }

    // ---- Baby profiles (multi-baby) ------------------------------------------

    private suspend fun refreshProfiles() {
        repo.assignLegacyDataToActiveProfile()
        _profiles.value = repo.getProfiles()
        _profile.value = repo.getProfile()
    }

    /** Add a new (blank) baby and make it active; the parent fills details in Settings. */
    fun addBaby() {
        viewModelScope.launch {
            repo.addProfile("", null)
            refreshProfiles()
            refreshActiveProfileState()
            _home.update { it.copy(message = trS("Συμπλήρωσε τα στοιχεία του νέου μωρού.")) }
        }
    }

    fun selectBaby(id: String) {
        viewModelScope.launch {
            clearHomeForProfileSwitch()
            _pending.value = null
            repo.setActiveProfile(id)
            refreshProfiles()
            refreshActiveProfileState()
        }
    }

    fun openPendingForBaby(id: String?, eventId: Long?) {
        viewModelScope.launch {
            clearHomeForProfileSwitch()
            _pending.value = null
            if (!id.isNullOrBlank() && repo.hasProfile(id)) {
                repo.setActiveProfile(id)
                eventId?.takeIf { it > 0L }?.let { repo.focusPendingEvent(id, it) }
            }
            refreshProfiles()
            refreshActiveProfileState()
        }
    }

    fun deleteBaby(id: String) {
        viewModelScope.launch {
            clearHomeForProfileSwitch()
            _pending.value = null
            ConfirmReminder.cancelAllForProfile(getApplication<Application>(), id, repo.pendingEventIds(id))
            FeedReminder.cancel(getApplication<Application>(), id)
            TummyReminder.cancel(getApplication<Application>(), id)
            repo.deleteProfile(id)
            refreshProfiles()
            refreshActiveProfileState()
        }
    }

    /**
     * Drop any on-screen analysis/recording tied to the previous baby so the Home header and
     * result card cannot disagree after a profile switch (and feedback cannot hit the wrong event).
     */
    private fun clearHomeForProfileSwitch() {
        if (_home.value.phase == Phase.RECORDING) {
            cancelRequested = true
            recorder.stop()
        }
        lastWaveform = null
        _home.value = HomeUiState(phase = Phase.IDLE)
    }

    fun saveProfile(
        name: String,
        birthMillis: Long?,
        gender: BabyGender,
        onDone: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            repo.updateActiveProfile(name.trim(), birthMillis, gender)
            refreshProfiles()
            scheduleFeedReminder()
            scheduleTummyReminder()
            _home.update { it.copy(message = trS("Το προφίλ αποθηκεύτηκε.")) }
            onDone?.invoke()
        }
    }

    /** First-run: save the baby profile and never show the welcome screen again. */
    fun completeOnboarding(
        name: String,
        birthMillis: Long?,
        colicConfirmed: Boolean = false,
        gender: BabyGender = BabyGender.UNKNOWN,
        initialWeightGrams: Int? = null,
        initialHeightMillimeters: Int? = null,
    ) {
        viewModelScope.launch {
            val id = repo.addProfile(name.trim(), birthMillis, colicConfirmed, gender)
            if (initialWeightGrams != null && initialWeightGrams > 0 && birthMillis != null) {
                repo.logWeight(
                    initialWeightGrams,
                    timestamp = birthMillis,
                    profileId = id,
                )
            }
            if (initialHeightMillimeters != null && initialHeightMillimeters > 0 && birthMillis != null) {
                repo.logHeight(
                    initialHeightMillimeters,
                    timestamp = birthMillis,
                    profileId = id,
                )
            }
            repo.setOnboardingComplete()
            refreshProfiles()
            refreshActiveProfileState()
            _onboardingComplete.value = true
        }
    }

    private suspend fun refreshActiveProfileState() {
        repo.refreshPersonalization()
        refreshPendingNow()
        refreshActiveFeeding()
        refreshActiveSleep()
        scheduleFeedReminder()
        scheduleTummyReminder()
    }

    fun skipOnboarding() {
        viewModelScope.launch {
            repo.addProfile("", null)
            repo.setOnboardingComplete()
            refreshProfiles()
            refreshActiveProfileState()
            _onboardingComplete.value = true
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            val profileId = repo.currentProfileId()
            val pendingIds = repo.pendingEventIds(profileId)
            repo.clearHistory()
            ConfirmReminder.cancelAllForProfile(getApplication<Application>(), profileId, pendingIds)
            _pending.value = null
            refreshActiveFeeding()
            refreshActiveSleep()
            scheduleFeedReminder()
            _home.update { it.copy(message = trS("Το ιστορικό & τα στατιστικά μηδενίστηκαν.")) }
        }
    }

    fun deleteEvent(id: Long) {
        viewModelScope.launch {
            val profileId = repo.profileIdForEvent(id)
            repo.deleteEvent(id)
            if (profileId != null) ConfirmReminder.cancel(getApplication<Application>(), profileId, id)
            refreshPendingNow()
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            repo.refreshPersonalization()
            _home.update { it.copy(message = trS("Ανανεώθηκε.")) }
        }
    }

    suspend fun exportReportHtml(): String = repo.exportReportHtml()

    suspend fun exportBackupJson(): String = repo.exportBackupJson()

    fun markBackupCreated() {
        repo.markBackupCreated()
        _lastBackupAt.value = repo.lastBackupAt()
    }

    fun importBackup(json: String) {
        viewModelScope.launch {
            try {
                val n = repo.importBackupJson(json)
                refreshProfiles()
                _language.value = repo.getLanguage()
                _personalizationEnabled.value = repo.isPersonalizationEnabled()
                _tummyReminderEnabled.value = repo.isTummyReminderEnabled()
                _tummyReminderHourAm.value = repo.tummyReminderHourAm()
                _tummyReminderHourPm.value = repo.tummyReminderHourPm()
                refreshActiveProfileState()
                schedulePendingConfirmations()
                _home.update { it.copy(message = restoreCompleteMessage(n)) }
            } catch (t: Throwable) {
                _home.update {
                    it.copy(message = "${trS("Σφάλμα επαναφοράς:")} ${t.message ?: trS("άκυρο αρχείο")}")
                }
            }
        }
    }

    fun dismissResult() {
        _home.update { HomeUiState(phase = Phase.IDLE) }
    }

    fun consumeMessage() {
        _home.update { it.copy(message = null) }
    }

    fun setPersonalization(enabled: Boolean) {
        repo.setPersonalizationEnabled(enabled)
        _personalizationEnabled.value = enabled
    }

    /** Toggle pediatrician-confirmed colic/gas for the active baby (per-baby context prior). */
    fun setColicConfirmed(enabled: Boolean) {
        viewModelScope.launch {
            repo.setColicConfirmed(enabled)
            refreshProfiles()
        }
    }

    fun resetPersonalization() {
        viewModelScope.launch {
            repo.resetPersonalization()
            _home.update { it.copy(message = trS("Η προσωποποίηση μηδενίστηκε.")) }
        }
    }

    fun setLanguage(lang: AppLang) {
        repo.setLanguage(lang)
        _language.value = lang
    }

    suspend fun loadStats(): StatsSummary = repo.stats()

    override fun onCleared() {
        recorder.stop()
        player.stop()
        _playback.value = PlaybackUiState()
        feedingJob?.cancel()
        sleepJob?.cancel()
        soother.stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_RECORD_MS = 7000
        const val MIN_LISTEN_MS = 2500 // capture at least this much before auto-finishing
        const val REMINDER_DELAY_MIN = 15L // ask "why did it cry?" ~15 min later (once the cause is clear)
        const val BACKUP_STALE_MS = 30L * 24 * 60 * 60 * 1000 // remind to back up monthly

        private fun recordedMessage(reason: CryReason): String = when (currentAppLang) {
            AppLang.EN -> "Recorded: ${trS(reason.displayName)}. Thanks!"
            AppLang.EL -> "Καταγράφηκε: ${reason.displayName}. Ευχαριστώ!"
        }

        private fun feedingCompleteMessage(durationMs: Long): String {
            val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return when (currentAppLang) {
                AppLang.EN -> "Feeding recorded: ${minutes}m ${seconds}s."
                AppLang.EL -> "Καταγράφηκε τάισμα: ${minutes}λ ${seconds}δ."
            }
        }

        private fun sleepCompleteMessage(durationMs: Long): String {
            val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return when (currentAppLang) {
                AppLang.EN -> "Sleep recorded: ${minutes}m ${seconds}s."
                AppLang.EL -> "Καταγράφηκε ύπνος: ${minutes}λ ${seconds}δ."
            }
        }

        private fun restoreCompleteMessage(n: Int): String = when (currentAppLang) {
            AppLang.EN -> "Restore complete ($n records)."
            AppLang.EL -> "Επαναφορά ολοκληρώθηκε ($n καταγραφές)."
        }
    }
}
