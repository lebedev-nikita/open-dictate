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

data class DictationState(
    val sessionId: Long = 0,
    val phase: DictationPhase = DictationPhase.IDLE,
    val operation: DictationOperation = DictationOperation.DICTATION,
    val model: TranscriptionModel? = null,
    val transcript: String = "",
    val message: String? = null,
) {
    init {
        require(!isActive || model != null) { "Active dictation requires a transcription model" }
    }

    val isActive: Boolean
        get() = phase.isActive

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
