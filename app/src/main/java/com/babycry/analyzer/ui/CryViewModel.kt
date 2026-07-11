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
import com.babycry.analyzer.data.StatsSummary
import com.babycry.analyzer.data.TummyTimeEvent
import com.babycry.analyzer.ml.CryAnalysis
import com.babycry.analyzer.model.AnalysisEngine
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

class CryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CryRepository.get(app)
    private val recorder = AudioRecorder()
    private val player = AudioPlayer()
    private val soother = SoothingPlayer()
    private var soothingJob: Job? = null
    private var lastWaveform: FloatArray? = null

    @Volatile
    private var cancelRequested = false

    val labels: List<CryReason> get() = repo.labels
    val hasModel: Boolean get() = repo.hasModel

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _personalizationEnabled = MutableStateFlow(true)
    val personalizationEnabled: StateFlow<Boolean> = _personalizationEnabled.asStateFlow()

    private val _profile = MutableStateFlow(repo.getProfile())
    val profile: StateFlow<BabyProfile> = _profile.asStateFlow()

    private val _profiles = MutableStateFlow(repo.getProfiles())
    val profiles: StateFlow<List<BabyProfile>> = _profiles.asStateFlow()

    private val _soothing = MutableStateFlow(SoothingUiState())
    val soothing: StateFlow<SoothingUiState> = _soothing.asStateFlow()

    private val _onboardingComplete = MutableStateFlow(repo.isOnboardingComplete())
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    // A past cry still awaiting the parent's "why did it cry?" confirmation.
    private val _pending = MutableStateFlow<CryEvent?>(null)
    val pendingConfirmation: StateFlow<CryEvent?> = _pending.asStateFlow()

    private val _language = MutableStateFlow(repo.getLanguage())
    val language: StateFlow<AppLang> = _language.asStateFlow()

    val canReplay: Boolean get() = lastWaveform != null

    val feedbackCount: StateFlow<Int> = repo.feedbackCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val recentEvents: StateFlow<List<CryEvent>> = repo.recentEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentFeedings: StateFlow<List<FeedingEvent>> = repo.recentFeedings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentDiapers: StateFlow<List<DiaperEvent>> = repo.recentDiapers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentTummy: StateFlow<List<TummyTimeEvent>> = repo.recentTummy()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _tummyReminderEnabled = MutableStateFlow(repo.isTummyReminderEnabled())
    val tummyReminderEnabled: StateFlow<Boolean> = _tummyReminderEnabled.asStateFlow()

    private val _tummyReminderHourAm = MutableStateFlow(repo.tummyReminderHourAm())
    val tummyReminderHourAm: StateFlow<Int> = _tummyReminderHourAm.asStateFlow()

    private val _tummyReminderHourPm = MutableStateFlow(repo.tummyReminderHourPm())
    val tummyReminderHourPm: StateFlow<Int> = _tummyReminderHourPm.asStateFlow()

    init {
        currentAppLang = repo.getLanguage()
        viewModelScope.launch { repo.refreshPersonalization() }
        refreshPending()
    }

    /** Re-check whether there's a past cry waiting for the parent to confirm its reason. */
    fun refreshPending() {
        viewModelScope.launch { _pending.value = repo.pendingConfirmation() }
    }

    /**
     * Shazam-style: tap the mic to start. There is no manual stop button - the analysis
     * auto-finishes as soon as it's confident (or when the max listen time is reached).
     */
    fun onListenTapped() {
        when (_home.value.phase) {
            Phase.IDLE, Phase.RESULT -> startListening()
            else -> Unit
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
                    ConfirmReminder.schedule(getApplication<Application>(), REMINDER_DELAY_MIN)
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
            repo.confirm(eventId, reason, embedding)
            if (_pending.value?.id == eventId) _pending.value = null
            ConfirmReminder.cancel(getApplication<Application>())
            _home.update { it.copy(feedbackGiven = true, message = trS("Ευχαριστώ! Θα μάθω από αυτό.")) }
        }
    }

    /** Confirm/correct the reason for the pending (previous) cry, from the home banner. */
    fun confirmPending(reason: CryReason) {
        val id = _pending.value?.id ?: return
        viewModelScope.launch {
            repo.setReason(id, reason)
            _pending.value = null
            ConfirmReminder.cancel(getApplication<Application>())
            _home.update {
                it.copy(message = recordedMessage(reason))
            }
        }
    }

    /** "Not sure yet" - stop asking for now (still editable later from History). */
    fun dismissPending() {
        repo.dismissPending()
        _pending.value = null
        ConfirmReminder.cancel(getApplication<Application>())
    }

    /** Set/correct the reason of any past cry (from History). */
    fun setReasonForEvent(eventId: Long, reason: CryReason) {
        viewModelScope.launch {
            repo.setReason(eventId, reason)
            if (_pending.value?.id == eventId) _pending.value = null
            _home.update { it.copy(message = trS("Ενημερώθηκε η αιτία.")) }
        }
    }

    suspend fun datasetInfo(): Pair<Int, Long> = repo.datasetInfo()

    suspend fun writeDatasetZip(out: OutputStream): Int = repo.writeDatasetZip(out)

    fun logFeeding() {
        viewModelScope.launch {
            repo.logFeeding()
            _home.update { it.copy(message = trS("Καταγράφηκε το τάισμα.")) }
            scheduleFeedReminder()
        }
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
            val plan = repo.feedReminderPlan()
            if (plan == null) {
                FeedReminder.cancel(app)
            } else {
                FeedReminder.schedule(app, plan.first, plan.second)
            }
        }
    }

    /** Play back the clip that was just analyzed. */
    fun playLastRecording() {
        val wave = lastWaveform ?: return
        viewModelScope.launch { player.play(wave) }
    }

    // ---- Saved recordings library --------------------------------------------

    /** Confirmed cries that still have a saved recording (reason + time + playable clip). */
    suspend fun libraryEvents(): List<CryEvent> = repo.libraryEvents()

    /** Replay a stored recording from the library. */
    fun playStoredClip(eventId: Long) {
        viewModelScope.launch {
            val wave = repo.readClipSamples(eventId) ?: return@launch
            player.play(wave)
        }
    }

    /** A shareable text summary of the current result, or null if there is nothing to share. */
    fun shareSummary(): String? {
        val analysis = _home.value.analysis ?: return null
        val r = analysis.result
        if (!r.cryDetected) return trS("«Γιατί Κλαίει;»: δεν ανιχνεύτηκε καθαρό κλάμα.")
        val top = r.topReason ?: return null
        val sb = StringBuilder()
        sb.append(trS("«Γιατί Κλαίει;» — αποτέλεσμα")).append("\n")
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

    private fun refreshProfiles() {
        _profiles.value = repo.getProfiles()
        _profile.value = repo.getProfile()
    }

    /** Add a new (blank) baby and make it active; the parent fills details in Settings. */
    fun addBaby() {
        viewModelScope.launch {
            repo.addProfile("", null)
            refreshProfiles()
            _home.update { it.copy(message = trS("Συμπλήρωσε τα στοιχεία του νέου μωρού.")) }
        }
    }

    fun selectBaby(id: String) {
        viewModelScope.launch {
            repo.setActiveProfile(id)
            refreshProfiles()
        }
    }

    fun deleteBaby(id: String) {
        viewModelScope.launch {
            repo.deleteProfile(id)
            refreshProfiles()
        }
    }

    fun saveProfile(name: String, birthMillis: Long?) {
        viewModelScope.launch {
            repo.updateActiveProfile(name.trim(), birthMillis)
            refreshProfiles()
            _home.update { it.copy(message = trS("Το προφίλ αποθηκεύτηκε.")) }
        }
    }

    /** First-run: save the baby profile and never show the welcome screen again. */
    fun completeOnboarding(name: String, birthMillis: Long?, colicConfirmed: Boolean = false) {
        viewModelScope.launch {
            repo.addProfile(name.trim(), birthMillis, colicConfirmed)
            repo.setOnboardingComplete()
            refreshProfiles()
            _onboardingComplete.value = true
        }
    }

    fun skipOnboarding() {
        repo.setOnboardingComplete()
        _onboardingComplete.value = true
    }

    fun clearHistory() {
        viewModelScope.launch {
            repo.clearHistory()
            _home.update { it.copy(message = trS("Το ιστορικό & τα στατιστικά μηδενίστηκαν.")) }
        }
    }

    fun deleteEvent(id: Long) {
        viewModelScope.launch { repo.deleteEvent(id) }
    }

    fun refreshData() {
        viewModelScope.launch {
            repo.refreshPersonalization()
            _home.update { it.copy(message = trS("Ανανεώθηκε.")) }
        }
    }

    suspend fun exportReportHtml(): String = repo.exportReportHtml()

    suspend fun exportBackupJson(): String = repo.exportBackupJson()

    fun importBackup(json: String) {
        viewModelScope.launch {
            try {
                val n = repo.importBackupJson(json)
                refreshProfiles()
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
        soother.stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_RECORD_MS = 7000
        const val MIN_LISTEN_MS = 2500 // capture at least this much before auto-finishing
        const val REMINDER_DELAY_MIN = 15L // ask "why did it cry?" ~15 min later (once the cause is clear)

        private fun recordedMessage(reason: CryReason): String = when (currentAppLang) {
            AppLang.EN -> "Recorded: ${trS(reason.displayName)}. Thanks!"
            AppLang.EL -> "Καταγράφηκε: ${reason.displayName}. Ευχαριστώ!"
        }

        private fun restoreCompleteMessage(n: Int): String = when (currentAppLang) {
            AppLang.EN -> "Restore complete ($n records)."
            AppLang.EL -> "Επαναφορά ολοκληρώθηκε ($n καταγραφές)."
        }
    }
}
