package com.babycry.analyzer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.babycry.analyzer.audio.AudioPlayer
import com.babycry.analyzer.audio.AudioRecorder
import com.babycry.analyzer.data.CryEvent
import com.babycry.analyzer.data.CryRepository
import com.babycry.analyzer.data.FeedingEvent
import com.babycry.analyzer.data.StatsSummary
import com.babycry.analyzer.ml.CryAnalysis
import com.babycry.analyzer.model.AnalysisEngine
import com.babycry.analyzer.model.BabyProfile
import com.babycry.analyzer.model.CryReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class Phase { IDLE, RECORDING, ANALYZING, RESULT }

data class HomeUiState(
    val phase: Phase = Phase.IDLE,
    val level: Float = 0f,
    val analysis: CryAnalysis? = null,
    val eventId: Long? = null,
    val feedbackGiven: Boolean = false,
    val message: String? = null,
)

class CryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CryRepository.get(app)
    private val recorder = AudioRecorder()
    private val player = AudioPlayer()
    private var lastWaveform: FloatArray? = null

    val labels: List<CryReason> get() = repo.labels
    val hasModel: Boolean get() = repo.hasModel

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _personalizationEnabled = MutableStateFlow(true)
    val personalizationEnabled: StateFlow<Boolean> = _personalizationEnabled.asStateFlow()

    private val _contextEnabled = MutableStateFlow(true)
    val contextEnabled: StateFlow<Boolean> = _contextEnabled.asStateFlow()

    private val _profile = MutableStateFlow(repo.getProfile())
    val profile: StateFlow<BabyProfile> = _profile.asStateFlow()

    private val _onboardingComplete = MutableStateFlow(repo.isOnboardingComplete())
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    val canReplay: Boolean get() = lastWaveform != null

    val feedbackCount: StateFlow<Int> = repo.feedbackCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val recentEvents: StateFlow<List<CryEvent>> = repo.recentEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentFeedings: StateFlow<List<FeedingEvent>> = repo.recentFeedings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { repo.refreshPersonalization() }
    }

    /** Shazam-style: tap to start listening, tap again to stop early. */
    fun onListenTapped() {
        when (_home.value.phase) {
            Phase.RECORDING -> recorder.stop()
            Phase.IDLE, Phase.RESULT -> startListening()
            Phase.ANALYZING -> Unit
        }
    }

    private fun startListening() {
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
                    HomeUiState(phase = Phase.IDLE, message = t.message ?: "Σφάλμα ηχογράφησης")
                }
                return@launch
            }
            lastWaveform = waveform
            _home.update { it.copy(phase = Phase.ANALYZING, level = 0f) }
            try {
                val analysis = repo.analyze(
                    waveform = waveform,
                    personalizationEnabled = _personalizationEnabled.value,
                    contextEnabled = _contextEnabled.value,
                )
                val eventId = repo.saveEvent(analysis)
                _home.update {
                    it.copy(phase = Phase.RESULT, analysis = analysis, eventId = eventId)
                }
            } catch (t: Throwable) {
                // Never let an inference error kill the app: surface it and reset.
                _home.update {
                    HomeUiState(
                        phase = Phase.IDLE,
                        message = "Σφάλμα ανάλυσης: ${t.message ?: t.javaClass.simpleName}",
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

    private fun submit(reason: CryReason) {
        val state = _home.value
        val eventId = state.eventId ?: return
        val embedding = state.analysis?.embedding
        viewModelScope.launch {
            repo.confirm(eventId, reason, embedding)
            _home.update { it.copy(feedbackGiven = true, message = "Ευχαριστώ! Θα μάθω από αυτό.") }
        }
    }

    fun logFeeding() {
        viewModelScope.launch {
            repo.logFeeding()
            _home.update { it.copy(message = "Καταγράφηκε το τάισμα.") }
        }
    }

    /** Play back the clip that was just analyzed. */
    fun playLastRecording() {
        val wave = lastWaveform ?: return
        viewModelScope.launch { player.play(wave) }
    }

    /** A shareable text summary of the current result, or null if there is nothing to share. */
    fun shareSummary(): String? {
        val analysis = _home.value.analysis ?: return null
        val r = analysis.result
        if (!r.cryDetected) return "«Γιατί Κλαίει;»: δεν ανιχνεύτηκε καθαρό κλάμα."
        val top = r.topReason ?: return null
        val sb = StringBuilder()
        sb.append("«Γιατί Κλαίει;» — αποτέλεσμα\n")
        sb.append("${top.emoji} ${top.displayName} (${(r.confidence * 100).roundToInt()}%)\n\n")
        sb.append("Πιθανές αιτίες:\n")
        r.scores.take(3).forEach {
            sb.append("• ${it.reason.displayName}: ${(it.probability * 100).roundToInt()}%\n")
        }
        sb.append("\n${top.advice}")
        return sb.toString()
    }

    fun saveProfile(name: String, birthMillis: Long?) {
        viewModelScope.launch {
            val p = BabyProfile(name = name.trim(), birthMillis = birthMillis)
            repo.setProfile(p)
            _profile.value = repo.getProfile()
            _home.update { it.copy(message = "Το προφίλ αποθηκεύτηκε.") }
        }
    }

    /** First-run: save the baby profile and never show the welcome screen again. */
    fun completeOnboarding(name: String, birthMillis: Long?) {
        viewModelScope.launch {
            repo.setProfile(BabyProfile(name = name.trim(), birthMillis = birthMillis))
            repo.setOnboardingComplete()
            _profile.value = repo.getProfile()
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
            _home.update { it.copy(message = "Το ιστορικό & τα στατιστικά μηδενίστηκαν.") }
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            repo.refreshPersonalization()
            _home.update { it.copy(message = "Ανανεώθηκε.") }
        }
    }

    suspend fun exportReportHtml(): String = repo.exportReportHtml()

    suspend fun exportBackupJson(): String = repo.exportBackupJson()

    fun importBackup(json: String) {
        viewModelScope.launch {
            try {
                val n = repo.importBackupJson(json)
                _profile.value = repo.getProfile()
                _home.update { it.copy(message = "Επαναφορά ολοκληρώθηκε ($n καταγραφές).") }
            } catch (t: Throwable) {
                _home.update { it.copy(message = "Σφάλμα επαναφοράς: ${t.message ?: "άκυρο αρχείο"}") }
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

    fun setContext(enabled: Boolean) {
        _contextEnabled.value = enabled
    }

    fun resetPersonalization() {
        viewModelScope.launch {
            repo.resetPersonalization()
            _home.update { it.copy(message = "Η προσωποποίηση μηδενίστηκε.") }
        }
    }

    suspend fun loadStats(): StatsSummary = repo.stats()

    override fun onCleared() {
        recorder.stop()
        player.stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_RECORD_MS = 7000
        const val MIN_LISTEN_MS = 2500 // capture at least this much before auto-finishing
    }
}
