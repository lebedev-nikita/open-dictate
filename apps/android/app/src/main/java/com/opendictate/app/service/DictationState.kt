package com.opendictate.app.service

import com.opendictate.app.model.TranscriptionModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DictationPhase {
    IDLE,
    CONNECTING,
    LISTENING,
    PROCESSING,
    COMPLETED,
    ERROR;

    val isProcessing: Boolean
        get() = this == PROCESSING

    val isActive: Boolean
        get() = this == CONNECTING || this == LISTENING || isProcessing
}

enum class DictationOperation {
    DICTATION,
    TRANSFORMATION,
}

enum class DictationProcessingStage {
    TRANSCRIPTION,
    POST_PROCESSING,
    TRANSFORMATION,
}

data class DictationState(
    val sessionId: Long = 0,
    val phase: DictationPhase = DictationPhase.IDLE,
    val operation: DictationOperation = DictationOperation.DICTATION,
    val model: TranscriptionModel? = null,
    val transcript: String = "",
    val message: String? = null,
    val processingStage: DictationProcessingStage? = null,
    val postProcessingEnabled: Boolean = false,
    val diagnostics: com.opendictate.app.model.ErrorContext? = null,
) {
    init {
        require(!isActive || model != null) { "Active dictation requires a transcription model" }
    }

    val isActive: Boolean
        get() = phase.isActive

    val activeProcessingStage: DictationProcessingStage?
        get() = if (!phase.isProcessing) null else processingStage ?: when (operation) {
            DictationOperation.DICTATION -> DictationProcessingStage.TRANSCRIPTION
            DictationOperation.TRANSFORMATION -> DictationProcessingStage.TRANSFORMATION
        }

    val showsProcessingIcon: Boolean
        get() = isActive && phase.isProcessing &&
            (activeProcessingStage != DictationProcessingStage.TRANSCRIPTION || postProcessingEnabled)

    internal fun acceptsActiveUpdate(sessionId: Long): Boolean =
        this.sessionId == sessionId && isActive

    internal fun acceptsCancellation(sessionId: Long): Boolean =
        sessionId != 0L && acceptsActiveUpdate(sessionId)

    internal fun shouldRestoreTextOnCancellation(): Boolean =
        operation == DictationOperation.DICTATION && model == TranscriptionModel.LIVE
}

object DictationStateBus {
    private val mutableState = MutableStateFlow(DictationState())
    val state = mutableState.asStateFlow()

    fun set(value: DictationState) {
        mutableState.value = value
    }
}
