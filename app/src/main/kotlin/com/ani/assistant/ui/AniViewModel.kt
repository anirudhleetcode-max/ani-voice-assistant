package com.ani.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ani.assistant.AppGraph
import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.core.permission.PermissionStatus
import com.ani.assistant.data.commands.SaveCommandResult
import com.ani.assistant.data.commands.StoredCommand
import com.ani.assistant.data.conversation.ConversationEntry
import com.ani.assistant.data.memory.MemoryCategory
import com.ani.assistant.data.memory.MemoryEntry
import com.ani.assistant.data.settings.AniSettings
import com.ani.assistant.voice.VoiceState
import com.ani.nlu.command.CustomAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One line in the on-screen transcript. */
data class TranscriptLine(
    val fromUser: Boolean,
    val text: String,
    val timestampMillis: Long
)

/**
 * The single view model behind every screen.
 *
 * One rather than seven, because every screen in Ani reads from the same handful of
 * repositories and the only genuinely stateful thing in the app — the voice session — is
 * shared by all of them. Splitting it would mean seven factories wiring up the same graph.
 *
 * Screens themselves are stateless: they take state and callbacks, which keeps them
 * previewable and keeps the logic here where it can be tested.
 */
class AniViewModel(private val graph: AppGraph) : ViewModel() {

    // ---- Voice ------------------------------------------------------------------------

    val voiceState: StateFlow<VoiceState> = graph.voiceSession.state
    val audioLevel: StateFlow<Float> = graph.voiceSession.audioLevel
    val partialTranscript: StateFlow<String> = graph.voiceSession.partialTranscript
    val voiceError: StateFlow<String?> = graph.voiceSession.lastError

    private val _transcript = MutableStateFlow<List<TranscriptLine>>(emptyList())

    /** The live exchange, newest last. Separate from stored history. */
    val transcript: StateFlow<List<TranscriptLine>> = _transcript.asStateFlow()

    // ---- Settings and data --------------------------------------------------------------

    val settings: StateFlow<AniSettings> = graph.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AniSettings.DEFAULT)

    val history: StateFlow<List<ConversationEntry>> = graph.conversationRepository.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val memories: StateFlow<List<MemoryEntry>> = graph.memoryRepository.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val commands: StateFlow<List<StoredCommand>> = graph.commandRepository.commands
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val notificationCount: StateFlow<Int> = graph.notificationRepository.unread
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    private val _permissions = MutableStateFlow<Map<AniPermission, PermissionStatus>>(emptyMap())
    val permissions: StateFlow<Map<AniPermission, PermissionStatus>> = _permissions.asStateFlow()

    private val _saveCommandResult = MutableStateFlow<SaveCommandResult?>(null)
    val saveCommandResult: StateFlow<SaveCommandResult?> = _saveCommandResult.asStateFlow()

    init {
        refreshPermissions()
        viewModelScope.launch {
            graph.voiceSession.turns.collect { turn ->
                _transcript.value = _transcript.value +
                    TranscriptLine(true, turn.command.originalText, System.currentTimeMillis()) +
                    TranscriptLine(false, turn.response, System.currentTimeMillis())
            }
        }
    }

    // ---- Actions ------------------------------------------------------------------------

    fun onOrbTapped() {
        if (voiceState.value.isActive) {
            graph.voiceSession.stop()
        } else {
            graph.voiceSession.startListening(playChime = true)
        }
    }

    fun submitTypedCommand(text: String) {
        // The turns flow emits both sides of the exchange, so echoing the user line here
        // would show it twice.
        graph.voiceSession.submitText(text)
    }

    fun stopSpeaking() = graph.voiceSession.stop()

    fun clearVoiceError() = graph.voiceSession.clearError()

    fun clearTranscript() {
        _transcript.value = emptyList()
        viewModelScope.launch { graph.orchestrator.reset() }
    }

    fun refreshPermissions() {
        graph.permissionManager.refresh()
        _permissions.value = graph.permissionManager.statuses.value
    }

    fun updateSettings(transform: (AniSettings) -> AniSettings) {
        viewModelScope.launch { graph.settingsRepository.update(transform) }
    }

    fun completeOnboarding() {
        viewModelScope.launch { graph.settingsRepository.setOnboardingCompleted(true) }
    }

    // ---- History ------------------------------------------------------------------------

    fun deleteHistoryEntry(id: String) {
        viewModelScope.launch { graph.conversationRepository.delete(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { graph.conversationRepository.clear() }
    }

    // ---- Memory -------------------------------------------------------------------------

    fun rememberFact(category: MemoryCategory, key: String, value: String) {
        viewModelScope.launch { graph.memoryRepository.remember(category, key, value) }
    }

    fun forgetMemory(id: String) {
        viewModelScope.launch { graph.memoryRepository.forget(id) }
    }

    fun clearMemory() {
        viewModelScope.launch { graph.memoryRepository.clear() }
    }

    // ---- Custom commands ------------------------------------------------------------------

    fun saveCommand(phrase: String, actions: List<CustomAction>, description: String = "") {
        viewModelScope.launch {
            _saveCommandResult.value = graph.commandRepository.save(phrase, actions, description)
        }
    }

    fun consumeSaveResult() {
        _saveCommandResult.value = null
    }

    fun setCommandEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { graph.commandRepository.setEnabled(id, enabled) }
    }

    fun deleteCommand(id: String) {
        viewModelScope.launch { graph.commandRepository.delete(id) }
    }

    fun runCommand(phrase: String) {
        submitTypedCommand(phrase)
    }

    // ---- Privacy ---------------------------------------------------------------------------

    fun deleteAllUserData() {
        viewModelScope.launch {
            graph.wipeAllUserData()
            _transcript.value = emptyList()
        }
    }

    /** Factory, so the graph can be handed in without a DI framework. */
    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AniViewModel::class.java)) {
                "Unknown ViewModel: ${modelClass.name}"
            }
            return AniViewModel(graph) as T
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
