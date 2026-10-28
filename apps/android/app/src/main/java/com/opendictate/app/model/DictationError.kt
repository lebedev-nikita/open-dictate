package com.opendictate.app.model

import kotlinx.coroutines.CancellationException

/** Expected failures contain safe metadata only, never exceptions or provider text. */
sealed class DictationError(final override val failureDetails: FailureDetails) : DiagnosticFailure {
    data class Http(val status: Int) : DictationError(FailureDetails.http(status))
    data object Network : DictationError(FailureDetails(ErrorReason.NETWORK))
    data object Timeout : DictationError(FailureDetails(ErrorReason.TIMEOUT))
    data object InvalidResponse : DictationError(FailureDetails(ErrorReason.INVALID_RESPONSE))
    data object EmptyResult : DictationError(FailureDetails(ErrorReason.EMPTY_RESULT))
    data object StreamIncomplete : DictationError(FailureDetails(ErrorReason.STREAM_INCOMPLETE))
    data class Provider(val details: FailureDetails = FailureDetails(ErrorReason.PROVIDER)) : DictationError(details)
    data class ChatGPT(val details: FailureDetails) : DictationError(details)
    data object MicrophonePermission : DictationError(FailureDetails(ErrorReason.MICROPHONE_PERMISSION))
    data object MissingApiKey : DictationError(FailureDetails(ErrorReason.MISSING_API_KEY))
    data object MissingRecording : DictationError(FailureDetails(ErrorReason.MISSING_RECORDING))
    data object NothingToTransform : DictationError(FailureDetails(ErrorReason.EMPTY_RESULT))
    data object EmptyTransformation : DictationError(FailureDetails(ErrorReason.EMPTY_RESULT))
    data class Rejected(val reason: ErrorReason) : DictationError(FailureDetails(reason)) {
        init {
            require(reason in setOf(ErrorReason.WORDS_CHANGED, ErrorReason.NUMBERS_CHANGED,
                ErrorReason.SPACING_CHANGED, ErrorReason.VALUE_CHANGED))
        }
    }
    data object Unexpected : DictationError(FailureDetails(ErrorReason.UNKNOWN))

    companion object {
        fun from(error: Throwable): DictationError {
            if (error is CancellationException) throw error
            val details = FailureDetails.from(error)
            return when (details.reason) {
                ErrorReason.NETWORK -> Network
                ErrorReason.TIMEOUT -> Timeout
                ErrorReason.INVALID_RESPONSE -> InvalidResponse
                ErrorReason.EMPTY_RESULT -> EmptyResult
                ErrorReason.STREAM_INCOMPLETE -> StreamIncomplete
                ErrorReason.MICROPHONE_PERMISSION -> MicrophonePermission
                ErrorReason.UNKNOWN -> Unexpected
                else -> Provider(details)
            }
        }
    }
}
