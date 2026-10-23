package com.opendictate.app.model

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONException

enum class ErrorStage { RECORDING, TRANSCRIPTION, POST_PROCESSING, TRANSFORMATION, DELIVERY }
enum class ErrorRoute { DEVICE, OPENAI_API, CHATGPT }
enum class ErrorOperation { DICTATION, VOICE_EDIT, RETRY }
enum class ErrorReason {
    NETWORK, TIMEOUT, HTTP, AUTHENTICATION, PROVIDER, STREAM_INCOMPLETE, INVALID_RESPONSE,
    EMPTY_RESULT, WORDS_CHANGED, NUMBERS_CHANGED, SPACING_CHANGED, VALUE_CHANGED,
    MICROPHONE_PERMISSION, MISSING_API_KEY, MISSING_RECORDING, DELIVERY_REJECTED, UNKNOWN,
}

/** Only bundled identifiers may enter diagnostics; never retain arbitrary settings strings. */
enum class DiagnosticModel(val id: String) {
    LIVE("gpt-live-transcribe"), ACCURATE("gpt-transcribe"), TEXT("gpt-6-luna"), OTHER("other");

    companion object {
        fun fromId(id: String): DiagnosticModel = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

data class FailureDetails(val reason: ErrorReason, val httpStatus: Int? = null) {
    init { require(httpStatus == null || httpStatus in 100..599) }

    companion object {
        fun http(status: Int) = FailureDetails(
            if (status == 401 || status == 403) ErrorReason.AUTHENTICATION else ErrorReason.HTTP, status,
        )

        /** Classify types only. Exception messages, provider bodies and stack traces are never copied. */
        fun from(error: Throwable): FailureDetails {
            val causes = generateSequence(error) { it.cause.takeUnless { cause -> cause === it } }.take(8).toList()
            causes.firstNotNullOfOrNull { (it as? DiagnosticFailure)?.failureDetails }?.let { return it }
            val reason = when {
                causes.any { it is TimeoutCancellationException || it is InterruptedIOException } -> ErrorReason.TIMEOUT
                causes.any { it is EOFException } -> ErrorReason.STREAM_INCOMPLETE
                causes.any { it is JSONException } -> ErrorReason.INVALID_RESPONSE
                causes.any { it is SecurityException } -> ErrorReason.MICROPHONE_PERMISSION
                causes.any { it is IOException } -> ErrorReason.NETWORK
                else -> ErrorReason.UNKNOWN
            }
            return FailureDetails(reason)
        }
    }
}

interface DiagnosticFailure { val failureDetails: FailureDetails? }
class DiagnosticException(override val failureDetails: FailureDetails) : Exception("Request failed"), DiagnosticFailure

data class ErrorContext(
    val attemptId: String,
    val stage: ErrorStage,
    val route: ErrorRoute,
    val operation: ErrorOperation,
    val mode: TranscriptionModel,
    val model: DiagnosticModel? = null,
)
