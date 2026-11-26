package com.opendictate.app.model

/** How an external dictation request ended, before it is encoded for the calling app. */
sealed interface ExternalDictationOutcome {
    data class Transcribed(val text: String) : ExternalDictationOutcome
    data object Cancelled : ExternalDictationOutcome
    data object NotAllowed : ExternalDictationOutcome
    data object Busy : ExternalDictationOutcome
    data class Failed(val error: DictationError) : ExternalDictationOutcome
}

/**
 * Activity result for `android.speech.action.RECOGNIZE_SPEECH`. Codes follow `RecognizerIntent`;
 * [error] is a stable machine-readable value and never contains provider text.
 */
data class ExternalDictationResult(val resultCode: Int, val text: String?, val error: String?)

object ExternalDictationContract {
    const val EXTRA_ERROR = "com.opendictate.app.extra.ERROR"
    const val EXTRA_TEXT = "com.opendictate.app.extra.TEXT"

    // Values of android.app.Activity and android.speech.RecognizerIntent, kept here for JVM tests.
    const val RESULT_OK = -1
    const val RESULT_CANCELED = 0
    const val RESULT_NO_MATCH = 1
    const val RESULT_CLIENT_ERROR = 2
    const val RESULT_SERVER_ERROR = 3
    const val RESULT_NETWORK_ERROR = 4
    const val RESULT_AUDIO_ERROR = 5
}

fun ExternalDictationOutcome.toResult(): ExternalDictationResult = with(ExternalDictationContract) {
    when (this@toResult) {
        is ExternalDictationOutcome.Transcribed -> ExternalDictationResult(RESULT_OK, text, null)
        ExternalDictationOutcome.Cancelled -> failure(RESULT_CANCELED, "cancelled")
        ExternalDictationOutcome.NotAllowed -> failure(RESULT_CLIENT_ERROR, "not_allowed")
        ExternalDictationOutcome.Busy -> failure(RESULT_CLIENT_ERROR, "busy")
        is ExternalDictationOutcome.Failed -> when (error) {
            DictationError.MissingApiKey -> failure(RESULT_CLIENT_ERROR, "missing_api_key")
            DictationError.MicrophonePermission -> failure(RESULT_AUDIO_ERROR, "microphone_permission")
            DictationError.Network -> failure(RESULT_NETWORK_ERROR, "network")
            DictationError.Timeout -> failure(RESULT_NETWORK_ERROR, "timeout")
            DictationError.EmptyResult, DictationError.EmptyTransformation -> failure(RESULT_NO_MATCH, "empty")
            is DictationError.Http, is DictationError.Provider, is DictationError.ChatGPT,
            DictationError.InvalidResponse, DictationError.StreamIncomplete, is DictationError.Rejected,
            -> failure(RESULT_SERVER_ERROR, "server")
            DictationError.MissingRecording, DictationError.NothingToTransform, DictationError.Unexpected,
            -> failure(RESULT_CLIENT_ERROR, "unexpected")
        }
    }
}

private fun failure(code: Int, error: String) = ExternalDictationResult(code, null, error)

/** Decides whether a caller may start recording before any microphone access. */
enum class ExternalCallerAccess { REJECTED, ASK, ALLOWED }

fun externalCallerAccess(callingPackage: String?, allowedPackages: Set<String>): ExternalCallerAccess = when {
    // Only a caller that waits for a result can be identified, so other launches are refused.
    callingPackage.isNullOrBlank() -> ExternalCallerAccess.REJECTED
    callingPackage in allowedPackages -> ExternalCallerAccess.ALLOWED
    else -> ExternalCallerAccess.ASK
}
