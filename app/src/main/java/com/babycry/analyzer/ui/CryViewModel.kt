package com.babycry.analyzer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.babycry.analyzer.audio.AudioRecorder
import com.babycry.analyzer.data.CryEvent
import com.babycry.analyzer.data.CryRepository
import com.babycry.analyzer.data.FeedingEvent
import com.babycry.analyzer.data.StatsSummary
import com.babycry.analyzer.ml.CryAnalysis
import com.babycry.analyzer.model.CryReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    val labels: List<CryReason> get() = repo.labels
    val hasModel: Boolean get() = repo.hasModel

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _personalizationEnabled = MutableStateFlow(true)
    val personalizationEnabled: StateFlow<Boolean> = _personalizationEnabled.asStateFlow()

    private val _contextEnabled = MutableStateFlow(true)
    val contextEnabled: StateFlow<Boolean> = _contextEnabled.asStateFlow()

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
                recorder.record(maxDurationMs = MAX_RECORD_MS) { level ->
                    _home.update { it.copy(level = level) }
                }
            } catch (t: Throwable) {
                _home.update {
                    HomeUiState(phase = Phase.IDLE, message = t.message ?: "Σφάλμα ηχογράφησης")
                }
                return@launch
            }
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

    suspend fun exportCsv(): String = repo.exportCsv()

    override fun onCleared() {
        recorder.stop()
        super.onCleared()
    }

    private companion object {
        const val MAX_RECORD_MS = 7000
    }
}
